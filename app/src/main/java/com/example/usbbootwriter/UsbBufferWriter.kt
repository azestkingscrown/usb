package com.example.usbbootwriter

interface UsbBufferWriter {
    fun openFile(path: String)
    fun writeBuffer(buffer: ByteArray)
    fun closeFile()
    fun updateProgress(progress: Float)
}
