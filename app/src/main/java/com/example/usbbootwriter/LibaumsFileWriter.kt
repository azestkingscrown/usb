package com.example.usbbootwriter

import android.util.Log
import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException
import java.nio.ByteBuffer

class LibaumsFileWriter(private val rootDir: UsbFile, private val onProgress: (Float) -> Unit = {}) : UsbBufferWriter {
    private var currentFile: UsbFile? = null
    private var currentOffset: Long = 0

    override fun openFile(path: String) {
        // Ensure paths use forward slash for processing
        val normalizedPath = path.replace("\\", "/").trim('/')
        Log.i("LibaumsFileWriter", "Opening file: $normalizedPath")

        val pathParts = normalizedPath.split("/")
        var currentDir = rootDir

        // Create directories if they don't exist
        for (i in 0 until pathParts.size - 1) {
            val dirName = pathParts[i]
            if (dirName.isEmpty()) continue
            
            var nextDir: UsbFile? = null
            for (file in currentDir.listFiles()) {
                if (file.isDirectory && file.name.equals(dirName, ignoreCase = true)) {
                    nextDir = file
                    break
                }
            }
            if (nextDir == null) {
                nextDir = currentDir.createDirectory(dirName)
            }
            currentDir = nextDir
        }

        // Create or open the file
        val fileName = pathParts.last()
        var fileToOpen: UsbFile? = null
        for (file in currentDir.listFiles()) {
            if (!file.isDirectory && file.name.equals(fileName, ignoreCase = true)) {
                fileToOpen = file
                break
            }
        }
        
        if (fileToOpen == null) {
            fileToOpen = currentDir.createFile(fileName)
        } else {
            fileToOpen.length = 0 // Truncate existing file
        }
        currentFile = fileToOpen
        currentOffset = 0
    }

    override fun writeBuffer(buffer: ByteArray) {
        val file = currentFile ?: throw IOException("No active file opened for writing")
        val byteBuffer = ByteBuffer.wrap(buffer)
        file.write(currentOffset, byteBuffer)
        currentOffset += buffer.size
    }

    override fun closeFile() {
        try {
            currentFile?.close()
        } finally {
            currentFile = null
            currentOffset = 0
        }
    }

    override fun updateProgress(progress: Float) {
        onProgress(progress)
    }
}
