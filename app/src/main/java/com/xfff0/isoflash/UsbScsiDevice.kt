package com.xfff0.isoflash

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable

class UsbScsiDevice(private val manager: UsbManager, val usbDevice: UsbDevice) : Closeable {

    val displayName: String
        get() = usbDevice.productName?.takeIf { it.isNotBlank() }
            ?: "USB %04x:%04x".format(usbDevice.vendorId, usbDevice.productId)

    // Non-nullable after init() — crash fix
    private lateinit var conn: UsbDeviceConnection
    private lateinit var epIn: UsbEndpoint
    private lateinit var epOut: UsbEndpoint
    private lateinit var iface: UsbInterface
    private var tagCounter = 1

    fun init() {
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                intf.interfaceSubclass == 0x06 &&
                intf.interfaceProtocol == 0x50) {
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                    }
                }
                val c = manager.openDevice(usbDevice) ?: error("لا يمكن فتح جهاز USB")
                // force=true releases kernel driver if any
                if (!c.claimInterface(intf, true)) {
                    c.close(); error("لا يمكن حجز واجهة USB")
                }
                conn = c
                epIn  = inEp  ?: run { c.close(); error("Bulk-IN endpoint مفقود") }
                epOut = outEp ?: run { c.close(); error("Bulk-OUT endpoint مفقود") }
                iface = intf
                return
            }
        }
        error("لم يُعثر على واجهة USB Mass Storage")
    }

    override fun close() {
        runCatching {
            if (::iface.isInitialized && ::conn.isInitialized) conn.releaseInterface(iface)
            if (::conn.isInitialized) conn.close()
        }
    }

    /** READ CAPACITY (10) → (totalSectors, blockSize) */
    fun readCapacity(): Pair<Long, Int> {
        val cdb = ByteArray(10).also { it[0] = 0x25.toByte() }
        val data = ByteArray(8)
        sendCommand(cdb, null, data)
        val lastLba = ((data[0].toLong() and 0xFF) shl 24) or
                      ((data[1].toLong() and 0xFF) shl 16) or
                      ((data[2].toLong() and 0xFF) shl  8) or
                       (data[3].toLong() and 0xFF)
        val blkSz   = ((data[4].toInt()  and 0xFF) shl 24) or
                      ((data[5].toInt()  and 0xFF) shl 16) or
                      ((data[6].toInt()  and 0xFF) shl  8) or
                       (data[7].toInt()  and 0xFF)
        return Pair(lastLba + 1, blkSz)
    }

    fun writeBlocks(lba: Long, data: ByteArray) {
        require(data.size % BLOCK_SIZE == 0) { "البيانات يجب أن تكون مضاعفاً لـ $BLOCK_SIZE" }
        val blockCount = data.size / BLOCK_SIZE
        val cdb = ByteArray(10).also { b ->
            b[0] = 0x2A
            b[2] = (lba shr 24).toByte(); b[3] = (lba shr 16).toByte()
            b[4] = (lba shr  8).toByte(); b[5] = lba.toByte()
            b[7] = (blockCount shr 8).toByte(); b[8] = blockCount.toByte()
        }
        sendCommand(cdb, data, null)
    }

    fun readBlocks(lba: Long, count: Int): ByteArray {
        val data = ByteArray(count * BLOCK_SIZE)
        val cdb = ByteArray(10).also { b ->
            b[0] = 0x28
            b[2] = (lba shr 24).toByte(); b[3] = (lba shr 16).toByte()
            b[4] = (lba shr  8).toByte(); b[5] = lba.toByte()
            b[7] = (count shr 8).toByte(); b[8] = count.toByte()
        }
        sendCommand(cdb, null, data)
        return data
    }

    private fun sendCommand(cdb: ByteArray, dataOut: ByteArray?, dataIn: ByteArray?) {
        val tag = tagCounter++
        val dataLen = dataOut?.size ?: dataIn?.size ?: 0
        val flags: Byte = if (dataIn != null) 0x80.toByte() else 0x00

        val cbw = ByteArray(31).also { b ->
            b[0]=0x55; b[1]=0x53; b[2]=0x42; b[3]=0x43
            b[4]=(tag).toByte();b[5]=(tag shr 8).toByte();b[6]=(tag shr 16).toByte();b[7]=(tag shr 24).toByte()
            b[8]=(dataLen).toByte();b[9]=(dataLen shr 8).toByte();b[10]=(dataLen shr 16).toByte();b[11]=(dataLen shr 24).toByte()
            b[12]=flags; b[13]=0x00; b[14]=cdb.size.toByte()
            System.arraycopy(cdb, 0, b, 15, cdb.size)
        }
        xfer(epOut, cbw)
        if (dataOut != null) xfer(epOut, dataOut)
        if (dataIn  != null) xferIn(epIn, dataIn)
        val csw = ByteArray(13)
        xferIn(epIn, csw)
        check(csw[12] == 0.toByte()) { "SCSI error: status=${csw[12].toInt() and 0xFF}" }
    }

    private fun xfer(ep: UsbEndpoint, data: ByteArray) {
        val n = conn.bulkTransfer(ep, data, data.size, TIMEOUT)
        check(n >= 0) { "Bulk-OUT transfer failed (ret=$n)" }
    }
    private fun xferIn(ep: UsbEndpoint, buf: ByteArray) {
        val n = conn.bulkTransfer(ep, buf, buf.size, TIMEOUT)
        check(n >= 0) { "Bulk-IN transfer failed (ret=$n)" }
    }

    companion object {
        const val BLOCK_SIZE = 512
        const val TIMEOUT    = 15_000

        fun findDevices(context: Context): List<UsbScsiDevice> {
            val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return mgr.deviceList.values.filter { dev ->
                (0 until dev.interfaceCount).any { i ->
                    val intf = dev.getInterface(i)
                    intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    intf.interfaceSubclass == 0x06 && intf.interfaceProtocol == 0x50
                }
            }.map { UsbScsiDevice(mgr, it) }
        }
    }
}
