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

    private var connection: UsbDeviceConnection? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var iface: UsbInterface? = null
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
                check(inEp != null && outEp != null) { "Missing bulk endpoints" }
                val conn = manager.openDevice(usbDevice) ?: error("Cannot open USB device")
                check(conn.claimInterface(intf, true)) { "Cannot claim interface" }
                connection = conn; bulkIn = inEp; bulkOut = outEp; iface = intf
                return
            }
        }
        error("No USB Mass Storage interface found")
    }

    override fun close() {
        runCatching { iface?.let { connection?.releaseInterface(it) }; connection?.close() }
        connection = null
    }

    fun writeBlocks(lba: Long, data: ByteArray) {
        require(data.size % BLOCK_SIZE == 0)
        val blockCount = data.size / BLOCK_SIZE
        val tag = tagCounter++
        val cbw = ByteArray(31).also { b ->
            b[0]=0x55;b[1]=0x53;b[2]=0x42;b[3]=0x43
            b[4]=(tag).toByte();b[5]=(tag shr 8).toByte();b[6]=(tag shr 16).toByte();b[7]=(tag shr 24).toByte()
            b[8]=(data.size).toByte();b[9]=(data.size shr 8).toByte();b[10]=(data.size shr 16).toByte();b[11]=(data.size shr 24).toByte()
            b[12]=0x00; b[13]=0x00; b[14]=10
            b[15]=0x2A
            b[17]=(lba shr 24).toByte();b[18]=(lba shr 16).toByte();b[19]=(lba shr 8).toByte();b[20]=(lba).toByte()
            b[22]=(blockCount shr 8).toByte();b[23]=(blockCount).toByte()
        }
        val conn = connection ?: error("Not open")
        check(conn.bulkTransfer(bulkOut, cbw, cbw.size, TIMEOUT) >= 0) { "CBW failed" }
        check(conn.bulkTransfer(bulkOut, data, data.size, TIMEOUT) >= 0) { "Data failed" }
        val csw = ByteArray(13)
        check(conn.bulkTransfer(bulkIn, csw, csw.size, TIMEOUT) >= 0) { "CSW failed" }
        check(csw[12] == 0.toByte()) { "SCSI error status=${csw[12]}" }
    }

    companion object {
        const val BLOCK_SIZE = 512
        const val TIMEOUT = 15_000

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
