package com.example.usbbootwriter

import android.util.Log
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Fat32Formatter {
    private const val TAG = "Fat32Formatter"
    private const val SECTOR_SIZE = 512
    private const val MBR_SIGNATURE = 0xAA55
    private const val FAT32_TYPE = 0x0C

    fun format(blockDevice: BlockDeviceDriver) {
        val blockSize = blockDevice.blockSize
        val totalBlocks = blockDevice.blocks
        Log.i(TAG, "Formatting device with $totalBlocks blocks of size $blockSize.")

        // We assume 512 byte block sizes for simplicity, which is standard for most USB drives
        if (blockSize != SECTOR_SIZE) {
            Log.w(TAG, "Device block size is $blockSize, assuming $SECTOR_SIZE for logical sectors.")
        }

        val partitionStartOffset = 2048L
        if (totalBlocks <= partitionStartOffset) {
            throw IOException("Device too small to format")
        }

        val partitionSize = totalBlocks - partitionStartOffset

        // 1. Write MBR at Sector 0
        val mbrBuffer = ByteBuffer.allocate(SECTOR_SIZE)
        mbrBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until SECTOR_SIZE) mbrBuffer.put(0)

        // Boot code: usually 446 bytes
        mbrBuffer.position(446)
        
        // Partition 1 Entry (16 bytes)
        mbrBuffer.put(0x80.toByte()) // Boot indicator (Active)
        mbrBuffer.put(0xFF.toByte()) // CHS Start
        mbrBuffer.put(0xFF.toByte())
        mbrBuffer.put(0xFF.toByte())
        mbrBuffer.put(FAT32_TYPE.toByte()) // Partition Type: FAT32 LBA
        mbrBuffer.put(0xFF.toByte()) // CHS End
        mbrBuffer.put(0xFF.toByte())
        mbrBuffer.put(0xFF.toByte())
        mbrBuffer.putInt(partitionStartOffset.toInt()) // LBA Start
        mbrBuffer.putInt(partitionSize.toInt()) // LBA Size

        // MBR Signature
        mbrBuffer.position(510)
        mbrBuffer.putShort(MBR_SIGNATURE.toShort())

        mbrBuffer.position(0)
        blockDevice.write(0, mbrBuffer)

        // 2. Format FAT32 VBR (Volume Boot Record) at partitionStartOffset
        val vbrBuffer = ByteBuffer.allocate(SECTOR_SIZE)
        vbrBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until SECTOR_SIZE) vbrBuffer.put(0)

        // Jump Instruction
        vbrBuffer.position(0)
        vbrBuffer.put(0xEB.toByte())
        vbrBuffer.put(0x58.toByte())
        vbrBuffer.put(0x90.toByte())

        // OEM Name "MSDOS5.0"
        vbrBuffer.put("MSDOS5.0".toByteArray(Charsets.US_ASCII))

        val bytesPerSector = SECTOR_SIZE
        val sectorsPerCluster = 8 // 4KB clusters
        val reservedSectors = 32
        val fatCount = 2
        val mediaDescriptor = 0xF8

        // Calculate FAT size
        val hiddenSectors = partitionStartOffset.toInt()
        val totalSectors32 = partitionSize.toInt()
        // FAT32 entries are 4 bytes. In a 512-byte sector, there are 128 entries.
        // The formula for sectorsPerFat is roughly: (totalSectors32 - reservedSectors) / (sectorsPerCluster * 128 + fatCount)
        val sectorsPerFat = ((totalSectors32 - reservedSectors) / (sectorsPerCluster * 128.0 + fatCount)).toInt() + 1
        
        val rootDirStartCluster = 2

        vbrBuffer.putShort(bytesPerSector.toShort()) // Bytes Per Sector
        vbrBuffer.put(sectorsPerCluster.toByte()) // Sectors Per Cluster
        vbrBuffer.putShort(reservedSectors.toShort()) // Reserved Sectors
        vbrBuffer.put(fatCount.toByte()) // Number of FATs
        vbrBuffer.putShort(0.toShort()) // Root Entries (0 for FAT32)
        vbrBuffer.putShort(0.toShort()) // Small Sectors (0 for FAT32)
        vbrBuffer.put(mediaDescriptor.toByte()) // Media Descriptor
        vbrBuffer.putShort(0.toShort()) // Sectors Per FAT (0 for FAT32)
        vbrBuffer.putShort(63.toShort()) // Sectors Per Track
        vbrBuffer.putShort(255.toShort()) // Number of Heads
        vbrBuffer.putInt(hiddenSectors) // Hidden Sectors
        vbrBuffer.putInt(totalSectors32) // Total Sectors 32

        // FAT32 Extended Boot Record
        vbrBuffer.putInt(sectorsPerFat) // Sectors Per FAT 32
        vbrBuffer.putShort(0.toShort()) // Ext Flags
        vbrBuffer.putShort(0.toShort()) // FS Version
        vbrBuffer.putInt(rootDirStartCluster) // Root Dir First Cluster
        vbrBuffer.putShort(1.toShort()) // FS Info Sector
        vbrBuffer.putShort(6.toShort()) // Backup Boot Sector

        vbrBuffer.position(vbrBuffer.position() + 12) // Reserved (12 bytes)

        vbrBuffer.put(0x80.toByte()) // Logical Drive Number
        vbrBuffer.put(0.toByte()) // Reserved
        vbrBuffer.put(0x29.toByte()) // Extended Signature
        vbrBuffer.putInt(0x12345678) // Volume Serial Number
        val volLabel = "USBBOOT    ".toByteArray(Charsets.US_ASCII)
        vbrBuffer.put(volLabel) // Volume Label (11 bytes)
        val sysType = "FAT32   ".toByteArray(Charsets.US_ASCII)
        vbrBuffer.put(sysType) // System Type (8 bytes)

        // Boot Sector Signature
        vbrBuffer.position(510)
        vbrBuffer.putShort(MBR_SIGNATURE.toShort())

        vbrBuffer.position(0)
        blockDevice.write(partitionStartOffset * SECTOR_SIZE, vbrBuffer)

        // Write Backup Boot Sector at Sector 6
        vbrBuffer.position(0)
        blockDevice.write((partitionStartOffset + 6) * SECTOR_SIZE, vbrBuffer)

        // 3. Write FS Info Sector at Sector 1
        val fsInfoBuffer = ByteBuffer.allocate(SECTOR_SIZE)
        fsInfoBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until SECTOR_SIZE) fsInfoBuffer.put(0)

        fsInfoBuffer.position(0)
        fsInfoBuffer.putInt(0x41615252) // Lead Signature
        fsInfoBuffer.position(484)
        fsInfoBuffer.putInt(0x61417272) // Struct Signature
        fsInfoBuffer.putInt(-1) // Free Count
        fsInfoBuffer.putInt(rootDirStartCluster + 1) // Next Free Cluster
        fsInfoBuffer.position(508)
        fsInfoBuffer.putInt(0xAA550000.toInt()) // Trail Signature

        fsInfoBuffer.position(0)
        blockDevice.write((partitionStartOffset + 1) * SECTOR_SIZE, fsInfoBuffer)

        // Write Backup FS Info Sector at Sector 7
        fsInfoBuffer.position(0)
        blockDevice.write((partitionStartOffset + 7) * SECTOR_SIZE, fsInfoBuffer)

        // 4. Initialize FAT Tables
        val fatStartSector = partitionStartOffset + reservedSectors
        
        val fatBuffer = ByteBuffer.allocate(SECTOR_SIZE)
        fatBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until SECTOR_SIZE) fatBuffer.put(0)

        // Cluster 0 and 1 are reserved
        fatBuffer.position(0)
        fatBuffer.putInt(0x0FFFFFF8.toInt()) // Cluster 0
        fatBuffer.putInt(0x0FFFFFFF.toInt()) // Cluster 1
        fatBuffer.putInt(0x0FFFFFFF.toInt()) // Cluster 2 (Root Dir, EOF)

        // Write the first sector of FAT1 and FAT2
        fatBuffer.position(0)
        blockDevice.write(fatStartSector * SECTOR_SIZE, fatBuffer)
        
        val fat2StartSector = fatStartSector + sectorsPerFat
        fatBuffer.position(0)
        blockDevice.write(fat2StartSector * SECTOR_SIZE, fatBuffer)

        // Zero out the rest of the FAT tables in batches to prevent hours of I/O latency
        val batchSectors = 2048 // 1MB batches
        val emptyFatBuffer = ByteBuffer.allocate(SECTOR_SIZE * batchSectors)
        for (i in 0 until emptyFatBuffer.capacity()) emptyFatBuffer.put(0)

        var written = 1
        while (written < sectorsPerFat) {
            val toWrite = minOf(batchSectors, sectorsPerFat - written)
            emptyFatBuffer.position(0)
            val chunk = emptyFatBuffer.slice()
            chunk.limit(toWrite * SECTOR_SIZE)
            blockDevice.write((fatStartSector + written) * SECTOR_SIZE, chunk)
            
            chunk.position(0)
            blockDevice.write((fat2StartSector + written) * SECTOR_SIZE, chunk)
            
            written += toWrite
        }

        // 5. Initialize Root Directory (Cluster 2)
        val rootDirStartSector = fatStartSector + (fatCount * sectorsPerFat)
        val rootDirBuffer = ByteBuffer.allocate(SECTOR_SIZE)
        for (i in 0 until SECTOR_SIZE) rootDirBuffer.put(0)
        
        for (i in 0 until sectorsPerCluster) {
            val offset = (rootDirStartSector + i) * SECTOR_SIZE
            rootDirBuffer.position(0)
            blockDevice.write(offset, rootDirBuffer)
        }

        Log.i(TAG, "FAT32 format complete.")
    }
}
