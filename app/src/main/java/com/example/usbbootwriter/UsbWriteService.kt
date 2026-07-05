package com.example.usbbootwriter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import java.io.FileDescriptor

class UsbWriteService : Service() {

    private val TAG = "UsbWriteService"
    private val CHANNEL_ID = "usb_write_service_channel"
    private val NOTIFICATION_ID = 101

    private val binder = LocalBinder()
    private var onProgressCallback: ((Float) -> Unit)? = null
    private var onCompleteCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    private var massStorageDevice: UsbMassStorageDevice? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): UsbWriteService = this@UsbWriteService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setCallbacks(
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        this.onProgressCallback = onProgress
        this.onCompleteCallback = onComplete
        this.onErrorCallback = onError
    }

    fun startBurn(usbDevice: UsbDevice, isoFd: ParcelFileDescriptor) {
        val notification = createNotification(0f)
        startForeground(NOTIFICATION_ID, notification)

        // Acquire WakeLock to keep CPU awake during transfer
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbBootWriter::FlashWakeLock")
            wakeLock?.acquire(30 * 60 * 1000L) // Safe limit 30 minutes
            Log.i(TAG, "WakeLock acquired successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val devices = UsbMassStorageDevice.getMassStorageDevices(this@UsbWriteService)
                massStorageDevice = devices.find { it.usbDevice.deviceName == usbDevice.deviceName }

                if (massStorageDevice == null) {
                    throw Exception("선택한 USB 장치를 Mass Storage로 열 수 없습니다.")
                }

                // Format USB block device
                val usbCommField = massStorageDevice?.javaClass?.getDeclaredField("usbCommunication")
                usbCommField?.isAccessible = true
                val usbComm = usbCommField?.get(massStorageDevice)

                if (usbComm != null) {
                    updateNotificationText("장치를 초기화 및 포맷하는 중...")
                    val factoryClass = Class.forName("me.jahnen.libaums.core.driver.BlockDeviceDriverFactory")
                    val factoryInstance = factoryClass.getDeclaredField("INSTANCE").get(null)
                    val createMethod = factoryClass.getMethod(
                        "createBlockDevice",
                        Class.forName("me.jahnen.libaums.core.usb.UsbCommunication"),
                        Byte::class.javaPrimitiveType
                    )
                    
                    val blockDevice = createMethod.invoke(factoryInstance, usbComm, 0.toByte()) as me.jahnen.libaums.core.driver.BlockDeviceDriver
                    Fat32Formatter.format(blockDevice)
                }

                massStorageDevice?.init()

                val currentFs = massStorageDevice?.partitions?.firstOrNull()?.fileSystem
                    ?: throw Exception("포맷 후 파티션을 인식할 수 없습니다.")

                val root = currentFs.rootDirectory
                
                // Native JNI 쓰기 호출
                val writer = LibaumsFileWriter(root, onProgress = { progress ->
                    // 1. Notification 갱신
                    updateProgressNotification(progress)
                    // 2. Activity 콜백 전달 (UI 갱신)
                    onProgressCallback?.invoke(progress)
                })

                updateNotificationText("ISO 파일을 USB에 복사 중...")
                
                // MainActivity JNI 메소드 바인딩 (이 서비스 인스턴스에서 직접 loadLibrary 호출 후 접근)
                val success = startWritingProcess(isoFd.fd, writer)
                if (!success) {
                    throw Exception("USB 쓰기 전송 중 하드웨어 시간초과 오류가 발생했습니다. USB가 분리되었거나 손상되었을 수 있습니다.")
                }

                // 완료 통보
                withContext(Dispatchers.Main) {
                    onCompleteCallback?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during background write service", e)
                withContext(Dispatchers.Main) {
                    onErrorCallback?.invoke(e.message ?: "알 수 없는 에러")
                }
            } finally {
                // Release WakeLock
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                        Log.i(TAG, "WakeLock released.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release WakeLock", e)
                }
                
                try {
                    massStorageDevice?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing storage", e)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "USB Boot Writer Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(progress: Float, statusText: String = "USB 굽기 진행 중..."): Notification {
        val pct = (progress * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USB Boot Writer")
            .setContentText("$statusText ($pct%)")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .build()
    }

    private fun updateProgressNotification(progress: Float) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(progress, "USB 복사 진행 중..."))
    }

    private fun updateNotificationText(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(0f, statusText))
    }

    // JNI 선언 호출 매핑
    private external fun startWritingProcess(isoFd: Int, writer: UsbBufferWriter): Boolean

    companion object {
        init {
            System.loadLibrary("usbbootwriter")
        }
    }
}
