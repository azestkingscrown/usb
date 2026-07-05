package com.example.usbbootwriter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val TAG = "UsbBootWriter-MainActivity"
    private val ACTION_USB_PERMISSION = "com.example.usbbootwriter.USB_PERMISSION"

    private var logFilePath: String = ""
    private var logProcess: Process? = null

    // USB 관련 상태
    private var usbManager: UsbManager? = null
    private var selectedDevice: UsbDevice? = null
    private var selectedDeviceNameState = mutableStateOf("선택된 USB 없음")
    private var deviceListState = mutableStateListOf<UsbDevice>()
    private var showDeviceSelector = mutableStateOf(false)
    
    // ISO 및 굽기 상태 추가
    private var selectedIsoNameState = mutableStateOf("선택된 ISO 없음")
    private var selectedIsoFd: ParcelFileDescriptor? = null
    private var isFlashingState = mutableStateOf(false)
    private var progressState = mutableStateOf(0f)

    private var massStorageDevice: UsbMassStorageDevice? = null

    // Background Service 관련 프로퍼티
    private var usbService: UsbWriteService? = null
    private var isServiceBound = false

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as UsbWriteService.LocalBinder
            usbService = binder.getService()
            isServiceBound = true
            
            // 연결 성공 시 즉시 굽기 시작
            val device = selectedDevice
            val isoFd = selectedIsoFd
            if (device != null && isoFd != null) {
                usbService?.setCallbacks(
                    onProgress = { progress ->
                        runOnUiThread {
                            progressState.value = progress
                        }
                    },
                    onComplete = {
                        runOnUiThread {
                            isFlashingState.value = false
                            Toast.makeText(this@MainActivity, "ISO 복사가 완료되었습니다.", Toast.LENGTH_LONG).show()
                            unbindBurnService()
                        }
                    },
                    onError = { errorMsg ->
                        runOnUiThread {
                            isFlashingState.value = false
                            Toast.makeText(this@MainActivity, "쓰기 중 에러 발생: $errorMsg", Toast.LENGTH_LONG).show()
                            unbindBurnService()
                        }
                    }
                )
                usbService?.startBurn(device, isoFd)
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            usbService = null
            isServiceBound = false
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "Notification permission granted.")
        } else {
            Toast.makeText(this, "알림 권한이 없으면 백그라운드 진행 상태를 볼 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unbindBurnService() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    // USB 권한 브로드캐스트 리시버
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String = intent.action ?: return
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.i(TAG, "Permission granted for device: ${device.deviceName}")
                            selectedDevice = device
                            selectedDeviceNameState.value = device.productName ?: device.deviceName
                            Toast.makeText(context, "USB 권한 획득 성공", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "Permission denied for device: ${device?.deviceName}")
                        Toast.makeText(context, "USB 접근 권한이 거부되었습니다.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        setupLogCapture()

        // Register USB permission receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val jniString = stringFromJNI()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        jniString = jniString,
                        logFilePath = logFilePath,
                        selectedDeviceName = selectedDeviceNameState.value,
                        selectedIsoName = selectedIsoNameState.value,
                        showDeviceSelector = showDeviceSelector.value,
                        deviceList = deviceListState,
                        isFlashing = isFlashingState.value,
                        progress = progressState.value,
                        onSelectUsbClick = { refreshUsbDevices() },
                        onDismissUsbDialog = { showDeviceSelector.value = false },
                        onDeviceSelected = { device -> requestUsbPermission(device) },
                        onSelectIsoClick = { launchIsoPicker() },
                        onStartWritingClick = { startWriting() }
                    )
                }
            }
        }
    }

    private fun startWriting() {
        val device = selectedDevice
        val isoFd = selectedIsoFd
        if (device == null || isoFd == null) {
            Toast.makeText(this, "USB와 ISO 파일을 모두 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        isFlashingState.value = true
        progressState.value = 0f

        val serviceIntent = Intent(this, UsbWriteService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun refreshUsbDevices() {
        deviceListState.clear()
        val devices = usbManager?.deviceList ?: return
        for (device in devices.values) {
            deviceListState.add(device)
        }
        if (deviceListState.isEmpty()) {
            Toast.makeText(this, "연결된 USB 디바이스를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            selectedDeviceNameState.value = "선택된 USB 없음"
            selectedDevice = null
        } else {
            showDeviceSelector.value = true
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        showDeviceSelector.value = false
        if (usbManager?.hasPermission(device) == true) {
            selectedDevice = device
            selectedDeviceNameState.value = device.productName ?: device.deviceName
            Toast.makeText(this, "이미 USB 권한을 보유하고 있습니다.", Toast.LENGTH_SHORT).show()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(packageName) // Set package explicitly for security on Android 14+
            }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
            usbManager?.requestPermission(device, permissionIntent)
        }
    }

    // Storage Access Framework 파일 피커 런처 등록
    private val selectIsoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                try {
                    // Close previous FD if any
                    selectedIsoFd?.close()
                    
                    // Retrieve filename
                    var filename = "selected.iso"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameIndex != -1) {
                            filename = cursor.getString(nameIndex)
                        }
                    }

                    // Open ParcelFileDescriptor
                    selectedIsoFd = contentResolver.openFileDescriptor(uri, "r")
                    if (selectedIsoFd != null) {
                        selectedIsoNameState.value = filename
                        Toast.makeText(this, "ISO 파일 로드 완료: $filename", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "ISO 파일 디스크립터를 획득하지 못했습니다.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening ISO file descriptor", e)
                    Toast.makeText(this, "파일 로딩 중 에러가 발생했습니다: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchIsoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Allow selecting any format to circumvent strict mime filtering on some builds
        }
        selectIsoLauncher.launch(intent)
    }

    private fun setupLogCapture() {
        val resolver = contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "app_debug.log")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents/UsbBootWriter")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            android.provider.MediaStore.Files.getContentUri("external")
        }

        // 기존 파일이 있는지 찾아보고 있으면 덮어쓰기 위해 query 진행
        var uri: Uri? = null
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        } else {
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("app_debug.log", "Documents/UsbBootWriter/")
        } else {
            arrayOf("app_debug.log")
        }

        try {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(idColumn)
                    uri = android.content.ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying existing log file", e)
        }

        if (uri == null) {
            uri = resolver.insert(collection, contentValues)
        }

        val finalUri = uri
        if (finalUri == null) {
            Log.e(TAG, "Failed to create shared log file URI")
            logFilePath = "공유 문서 폴더에 로그 파일 생성 실패"
            return
        }

        logFilePath = "내장메모리/Documents/UsbBootWriter/app_debug.log"

        try {
            Runtime.getRuntime().exec("logcat -c")
        } catch (e: IOException) {
            e.printStackTrace()
        }

        Thread {
            try {
                val pid = android.os.Process.myPid()
                // UsbBootWriter-Native 및 LibaumsFileWriter, MainActivity 로그 모두 캡처
                val command = "logcat -v threadtime --pid=$pid"
                logProcess = Runtime.getRuntime().exec(command)
                
                logProcess?.inputStream?.bufferedReader()?.use { reader ->
                    // Open output stream in append or overwrite mode
                    resolver.openOutputStream(finalUri, "rwt")?.use { outputStream ->
                        outputStream.bufferedWriter().use { writer ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentValues.clear()
                                contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                                resolver.update(finalUri, contentValues, null, null)
                            }
                            
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing logs to Shared Storage", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        logProcess?.destroy()
        unbindBurnService()
        try {
            selectedIsoFd?.close()
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Ignore unregister receiver if not registered
        }
    }

    companion object {
        init {
            System.loadLibrary("usbbootwriter")
        }
    }

    external fun stringFromJNI(): String

    external fun startWritingProcess(isoFd: Int, writer: UsbBufferWriter): Boolean
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    jniString: String = "",
    logFilePath: String = "",
    selectedDeviceName: String,
    selectedIsoName: String,
    showDeviceSelector: Boolean,
    deviceList: List<UsbDevice>,
    isFlashing: Boolean,
    progress: Float,
    onSelectUsbClick: () -> Unit,
    onDismissUsbDialog: () -> Unit,
    onDeviceSelected: (UsbDevice) -> Unit,
    onSelectIsoClick: () -> Unit,
    onStartWritingClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "USB Boot Writer", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = jniString, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onSelectUsbClick, enabled = !isFlashing) {
            Text(text = "USB 드라이브 선택")
        }
        Text(text = selectedDeviceName, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onSelectIsoClick, enabled = !isFlashing) {
            Text(text = "ISO 파일 선택")
        }
        Text(text = selectedIsoName, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(32.dp))

        if (logFilePath.isNotEmpty()) {
            Text(text = "Log File: $logFilePath", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(32.dp))
        }

        if (isFlashing) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "${(progress * 100).toInt()}% 완료")
        } else {
            Button(
                onClick = onStartWritingClick,
                enabled = selectedDeviceName != "선택된 USB 없음" && selectedIsoName != "선택된 ISO 없음"
            ) {
                Text(text = "USB 만들기 시작")
            }
        }
    }

    if (showDeviceSelector) {
        AlertDialog(
            onDismissRequest = onDismissUsbDialog,
            title = { Text(text = "USB 디바이스 선택") },
            text = {
                LazyColumn {
                    items(deviceList) { device ->
                        val displayName = "${device.productName ?: "알 수 없는 디바이스"} (${device.manufacturerName ?: "제조사 미확인"})"
                        Text(
                            text = displayName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device) }
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissUsbDialog) {
                    Text("취소")
                }
            }
        )
    }
}

