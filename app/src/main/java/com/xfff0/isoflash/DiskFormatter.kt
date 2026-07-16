package com.xfff0.isoflash

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Implements raw-sector disk formatting:
 *  - Wipe (zero fill)
 *  - MBR + FAT32
 *  - GPT + FAT32
 */
object DiskFormatter {

    enum class PartitionScheme { MBR, GPT }
    enum class FormatType      { FAT32, WIPE }

    data class Options(
        val scheme: PartitionScheme = PartitionScheme.MBR,
        val type:   FormatType      = FormatType.FAT32,
        val label:  String          = "ISOFLASH"
    )

    // ── Public entry point ─────────────────────────────────────────────────────
    fun format(
        device:     UsbScsiDevice,
        options:    Options,
        onProgress: (Float, String) -> Unit
    ) {
        val (totalSectors, blockSize) = device.readCapacity()
        check(blockSize == 512) { "فقط أقراص 512 بايت/قطاع مدعومة (هذا القرص: $blockSize)" }

        when (options.type) {
            FormatType.WIPE  -> wipe(device, totalSectors, onProgress)
            FormatType.FAT32 -> {
                onProgress(0.00f, "مسح بداية القرص…")
                zeroRange(device, 0, minOf(4096, totalSectors))

                when (options.scheme) {
                    PartitionScheme.MBR -> mbrFat32(device, totalSectors, options.label, onProgress)
                    PartitionScheme.GPT -> gptFat32(device, totalSectors, options.label, onProgress)
                }
            }
        }
    }

    // ── Wipe ───────────────────────────────────────────────────────────────────
    private fun wipe(device: UsbScsiDevice, total: Long, onProgress: (Float, String) -> Unit) {
        zeroRange(device, 0, total) { lba ->
            onProgress((lba.toFloat() / total).coerceIn(0f, 1f),
                "جاري المسح: ${lba * 512 / 1_048_576} MB / ${total * 512 / 1_048_576} MB")
        }
        onProgress(1f, "اكتمل المسح ✓")
    }

    // ── MBR + FAT32 ────────────────────────────────────────────────────────────
    private fun mbrFat32(device: UsbScsiDevice, total: Long, label: String, onProgress: (Float, String) -> Unit) {
        val partStart = 2048L
        val partSize  = total - partStart

        onProgress(0.05f, "كتابة MBR…")
        device.writeBlocks(0, buildMBR(partStart, partSize, type = 0x0C))

        onProgress(0.10f, "تهيئة FAT32…")
        writeFAT32(device, partStart, partSize, label, onProgress)
    }

    // ── GPT + FAT32 ────────────────────────────────────────────────────────────
    private fun gptFat32(device: UsbScsiDevice, total: Long, label: String, onProgress: (Float, String) -> Unit) {
        val partStart  = 2048L
        val partEnd    = total - 34          // last usable LBA
        val partSize   = partEnd - partStart + 1
        val diskGuid   = randomGuid()
        val partGuid   = randomGuid()

        onProgress(0.05f, "كتابة GPT…")
        val (pMbr, primHeader, entriesBlob, secHeader) =
            buildGPT(total, diskGuid, partGuid, partStart, partEnd, label)

        device.writeBlocks(0,            pMbr)
        device.writeBlocks(1,            primHeader)
        device.writeBlocks(2,            entriesBlob)          // 32 sectors of entries
        device.writeBlocks(total - 33,   entriesBlob)          // Secondary entries
        device.writeBlocks(total - 1,    secHeader)

        onProgress(0.10f, "تهيئة FAT32…")
        writeFAT32(device, partStart, partSize, label, onProgress)
    }

    // ── FAT32 writer ──────────────────────────────────────────────────────────
    private fun writeFAT32(
        device: UsbScsiDevice,
        partStart: Long,
        partSize: Long,
        label: String,
        onProgress: (Float, String) -> Unit
    ) {
        val spc      = sectorsPerCluster(partSize)    // sectors per cluster
        val reserved = 32                              // reserved sectors (includes boot, FSInfo, backup)
        val numFats  = 2
        val fatSec   = calcFatSectors(partSize, reserved, spc)
        val dataStart = reserved + numFats * fatSec
        val totalClusters = (partSize - dataStart) / spc

        // Boot + FSInfo + Backup boot
        onProgress(0.12f, "كتابة Boot Sector…")
        val boot = buildFAT32Boot(partSize, spc, reserved, fatSec, totalClusters, label)
        device.writeBlocks(partStart,      boot)                 // sector 0 of partition
        device.writeBlocks(partStart + 1,  buildFSInfo(totalClusters - 1))
        device.writeBlocks(partStart + 6,  boot)                 // backup boot
        device.writeBlocks(partStart + 7,  buildFSInfo(totalClusters - 1))

        // FAT1
        onProgress(0.20f, "كتابة FAT1…")
        writeFAT(device, partStart + reserved, fatSec, 0.20f, 0.55f, onProgress)

        // FAT2 (backup)
        onProgress(0.55f, "كتابة FAT2…")
        writeFAT(device, partStart + reserved + fatSec, fatSec, 0.55f, 0.90f, onProgress)

        // Root directory cluster (cluster 2)
        onProgress(0.90f, "تهيئة الجذر…")
        device.writeBlocks(partStart + dataStart, ByteArray(spc * 512))

        onProgress(1.00f, "اكتملت التهيئة ✓")
    }

    private fun writeFAT(
        device: UsbScsiDevice, fatStart: Long, fatSectors: Long,
        progStart: Float, progEnd: Float, onProgress: (Float, String) -> Unit
    ) {
        // First sector: media byte (0x0FFFFFF8), EOC (0x0FFFFFFF), root EOC (0x0FFFFFFF)
        val first = ByteArray(512).also { b ->
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(0x0FFFFFF8.toInt())
            bb.putInt(0x0FFFFFFF.toInt())
            bb.putInt(0x0FFFFFFF.toInt())
        }
        device.writeBlocks(fatStart, first)

        // Rest: zeros in 128-sector chunks
        val zeros = ByteArray(512 * 128)
        var lba = fatStart + 1
        val end = fatStart + fatSectors
        while (lba < end) {
            val cnt = minOf(128L, end - lba).toInt()
            device.writeBlocks(lba, if (cnt == 128) zeros else ByteArray(cnt * 512))
            lba += cnt
            val p = progStart + (lba - fatStart).toFloat() / fatSectors * (progEnd - progStart)
            onProgress(p.coerceIn(progStart, progEnd), "كتابة FAT… ${lba - fatStart}/$fatSectors قطاع")
        }
    }

    // ── MBR builder ───────────────────────────────────────────────────────────
    private fun buildMBR(partStart: Long, partSize: Long, type: Int): ByteArray {
        val mbr = ByteArray(512)
        val bb  = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)

        // Bootstrap code placeholder (x86 "invalid opcode" trap)
        mbr[0] = 0xEB.toByte(); mbr[1] = 0x58; mbr[2] = 0x90.toByte()

        // Partition entry 1 at offset 446
        bb.position(446)
        bb.put(0x80.toByte())                    // Status: bootable
        bb.put(encodeCHS(partStart))             // CHS First
        bb.put(type.toByte())                    // Partition type
        bb.put(encodeCHS(partStart + partSize - 1)) // CHS Last
        bb.putInt(partStart.toInt())             // LBA first sector
        bb.putInt(partSize.toInt())              // LBA size

        // Signature
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        return mbr
    }

    // ── GPT builder ───────────────────────────────────────────────────────────
    private data class GPTBlobs(val pmbr: ByteArray, val primaryHeader: ByteArray, val entries: ByteArray, val secondaryHeader: ByteArray)

    private fun buildGPT(
        total: Long, diskGuid: ByteArray, partGuid: ByteArray,
        partStart: Long, partEnd: Long, label: String
    ): GPTBlobs {
        // Partition entries: 128 entries × 128 bytes = 16384 bytes = 32 sectors
        val entries = ByteArray(32 * 512)
        val eBuf = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
        // Entry 0: Microsoft Basic Data partition
        eBuf.position(0)
        // Type GUID: EBD0A0A2-B9E5-4433-87C0-68B6B72699C7 (mixed-endian)
        val typeGuid = byteArrayOf(
            0xA2.toByte(),0xA0.toByte(),0xD0.toByte(),0xEB.toByte(),
            0xE5.toByte(),0xB9.toByte(),0x33.toByte(),0x44.toByte(),
            0x87.toByte(),0xC0.toByte(),0x68.toByte(),0xB6.toByte(),
            0xB7.toByte(),0x26.toByte(),0x99.toByte(),0xC7.toByte()
        )
        eBuf.put(typeGuid)
        eBuf.put(partGuid)
        eBuf.putLong(partStart)
        eBuf.putLong(partEnd)
        eBuf.putLong(0)           // attributes
        // Partition name (UTF-16LE, max 36 chars)
        val name = label.take(36)
        for (ch in name) { eBuf.putChar(ch) }

        val entriesCrc = crc32(entries)

        // Primary GPT header (512 bytes)
        val primH = buildGPTHeader(
            myLba = 1L, alternateLba = total - 1,
            firstUsable = 34L, lastUsable = total - 34,
            diskGuid = diskGuid, entriesLba = 2L,
            numEntries = 128, entrySize = 128,
            entriesCrc = entriesCrc
        )

        // Secondary GPT header
        val secH = buildGPTHeader(
            myLba = total - 1, alternateLba = 1L,
            firstUsable = 34L, lastUsable = total - 34,
            diskGuid = diskGuid, entriesLba = total - 33,
            numEntries = 128, entrySize = 128,
            entriesCrc = entriesCrc
        )

        // Protective MBR
        val pmbr = ByteArray(512)
        val pmbb = ByteBuffer.wrap(pmbr).order(ByteOrder.LITTLE_ENDIAN)
        pmbb.position(446)
        pmbb.put(0x00)                           // Not bootable
        pmbb.put(byteArrayOf(0x00, 0x02, 0x00)) // CHS first (fixed)
        pmbb.put(0xEE.toByte())                  // GPT protective partition
        pmbb.put(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) // CHS last
        pmbb.putInt(1)                           // LBA first
        pmbb.putInt(minOf(total - 1, 0xFFFFFFFFL).toInt()) // LBA size
        pmbr[510] = 0x55; pmbr[511] = 0xAA.toByte()

        return GPTBlobs(pmbr, primH, entries, secH)
    }

    private fun buildGPTHeader(
        myLba: Long, alternateLba: Long,
        firstUsable: Long, lastUsable: Long,
        diskGuid: ByteArray, entriesLba: Long,
        numEntries: Int, entrySize: Int, entriesCrc: Int
    ): ByteArray {
        val hdr = ByteArray(512)
        val bb  = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("EFI PART".toByteArray())         // Signature
        bb.putInt(0x00010000)                    // Revision 1.0
        bb.putInt(92)                            // Header size
        bb.putInt(0)                             // CRC placeholder
        bb.putInt(0)                             // Reserved
        bb.putLong(myLba)
        bb.putLong(alternateLba)
        bb.putLong(firstUsable)
        bb.putLong(lastUsable)
        bb.put(diskGuid)
        bb.putLong(entriesLba)
        bb.putInt(numEntries)
        bb.putInt(entrySize)
        bb.putInt(entriesCrc)
        // Compute and write header CRC
        val headerCrc = crc32(hdr.copyOf(92))
        ByteBuffer.wrap(hdr, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerCrc)
        return hdr
    }

    // ── FAT32 Boot Sector ─────────────────────────────────────────────────────
    private fun buildFAT32Boot(
        partSectors: Long, spc: Int, reserved: Int,
        fatSec: Long, totalClusters: Long, label: String
    ): ByteArray {
        val b = ByteArray(512)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

        // Jump + NOP
        b[0] = 0xEB.toByte(); b[1] = 0x58; b[2] = 0x90.toByte()
        // OEM Name
        val oem = "MSWIN4.1".toByteArray()
        System.arraycopy(oem, 0, b, 3, oem.size)

        bb.position(11)
        bb.putShort(512)                         // Bytes per sector
        bb.put(spc.toByte())                     // Sectors per cluster
        bb.putShort(reserved.toShort())          // Reserved sectors
        bb.put(2)                                // Number of FATs
        bb.putShort(0)                           // Root entry count (0 = FAT32)
        bb.putShort(0)                           // Total sectors 16 (0 = use 32-bit)
        bb.put(0xF8.toByte())                    // Media type (fixed disk)
        bb.putShort(0)                           // FAT size 16 (0 = FAT32)
        bb.putShort(63)                          // Sectors per track
        bb.putShort(255.toShort())               // Number of heads
        bb.putInt(0)                             // Hidden sectors
        bb.putInt(partSectors.toInt())           // Total sectors 32
        // FAT32 Extended BPB
        bb.putInt(fatSec.toInt())                // FAT size 32
        bb.putShort(0)                           // Ext flags
        bb.putShort(0)                           // FS Version
        bb.putInt(2)                             // Root cluster
        bb.putShort(1)                           // FSInfo sector
        bb.putShort(6)                           // Backup boot sector
        repeat(12) { bb.put(0) }                // Reserved
        bb.put(0x80.toByte())                    // Drive number
        bb.put(0)                               // Reserved1
        bb.put(0x29)                             // Boot signature
        bb.putInt((System.currentTimeMillis() / 1000).toInt()) // Volume ID

        val lbl = label.uppercase().padEnd(11).take(11)
        bb.put(lbl.toByteArray())                // Volume label
        bb.put("FAT32   ".toByteArray())         // FS type

        // Signature
        b[510] = 0x55; b[511] = 0xAA.toByte()
        return b
    }

    private fun buildFSInfo(freeClusters: Long): ByteArray {
        val b = ByteArray(512)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(0);  bb.putInt(0x41615252)  // Lead signature
        bb.position(484); bb.putInt(0x61417272) // Structure signature
        bb.putInt(freeClusters.toInt())          // Free cluster count
        bb.putInt(3)                             // Next free cluster hint
        bb.position(508); bb.putInt(0xAA550000.toInt()) // Trail signature
        return b
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun sectorsPerCluster(partSectors: Long): Int {
        val gb = partSectors * 512 / (1024L * 1024 * 1024)
        return when {
            gb <= 8  -> 8
            gb <= 16 -> 16
            gb <= 32 -> 32
            else     -> 64
        }
    }

    private fun calcFatSectors(partSectors: Long, reserved: Int, spc: Int): Long {
        // fatSectors = ceil((totalClusters + 2) * 4 / 512)
        // Iterative solution (FAT size depends on data area which depends on FAT size)
        var fatSec = 1L
        for (iter in 0..5) {
            val dataStart = reserved + 2 * fatSec
            val clusters = (partSectors - dataStart) / spc
            fatSec = ((clusters + 2) * 4 + 511) / 512
        }
        return fatSec
    }

    private fun zeroRange(device: UsbScsiDevice, start: Long, end: Long, onLba: ((Long) -> Unit)? = null) {
        val zeros = ByteArray(512 * 128)
        var lba = start
        while (lba < end) {
            val cnt = minOf(128L, end - lba).toInt()
            device.writeBlocks(lba, if (cnt == 128) zeros else ByteArray(cnt * 512))
            lba += cnt
            onLba?.invoke(lba)
        }
    }

    private fun encodeCHS(lba: Long): ByteArray {
        if (lba >= 16_450_560L) return byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val cyl  = (lba / (255 * 63)).toInt()
        val head = ((lba / 63) % 255).toInt()
        val sec  = (lba % 63 + 1).toInt()
        return byteArrayOf(head.toByte(), ((sec and 0x3F) or ((cyl shr 2) and 0xC0)).toByte(), (cyl and 0xFF).toByte())
    }

    private fun randomGuid(): ByteArray {
        val uuid = UUID.randomUUID()
        val bb = ByteBuffer.allocate(16)
        // GPT GUID is mixed-endian: first 3 fields LE, last 2 BE
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt((uuid.mostSignificantBits shr 32).toInt())
        bb.putShort(((uuid.mostSignificantBits shr 16) and 0xFFFF).toShort())
        bb.putShort((uuid.mostSignificantBits and 0xFFFF).toShort())
        bb.order(ByteOrder.BIG_ENDIAN)
        bb.putLong(uuid.leastSignificantBits)
        return bb.array()
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc32Table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc xor -1
    }

    private val crc32Table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }
}
