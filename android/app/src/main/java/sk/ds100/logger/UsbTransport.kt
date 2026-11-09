package sk.ds100.logger

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import sk.ds100.logger.core.Transport
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

const val DS100_VID = 0x1941
const val DS100_PID = 0x8021

fun UsbDevice.isDs100() = vendorId == DS100_VID && productId == DS100_PID

/**
 * HID transport through the Android USB host API. Like Linux hidraw: an output report is sent
 * with HID SET_REPORT on the control endpoint (or on the interrupt OUT endpoint if that fails),
 * input reports are read from the interrupt IN endpoint, one packet at a time.
 *
 * Reads must ask for exactly one packet (8 bytes on this low-speed device): a longer request
 * is not ended by the full-size 8-byte packets and runs into the timeout.
 *
 * When the logger is opened, a test read of the header picks a working read method
 * (bulkTransfer, or UsbRequest as fallback); if none works the error contains a USB report.
 */
class UsbTransport(manager: UsbManager, device: UsbDevice) : Transport() {
    private enum class Recv { BULK, REQUEST }

    private val conn: UsbDeviceConnection
    private val intf: UsbInterface = device.getInterface(0)
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint?
    private val len: Int
    private var recvMode = Recv.BULK
    private var useOutEp = false
    val report = StringBuilder()

    init {
        report.append("USB %04X:%04X, %d interface(s), Android %d\n".format(
            device.vendorId, device.productId, device.interfaceCount, Build.VERSION.SDK_INT))
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            report.append("  ep 0x%02X type %d dir %s maxPacket %d interval %d\n".format(
                e.address, e.type, if (e.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT", e.maxPacketSize, e.interval))
        }
        conn = manager.openDevice(device) ?: throw IOException("cannot open the USB device\n$report")
        if (!conn.claimInterface(intf, true)) {          // true: detach the kernel HID driver
            conn.close()
            throw IOException("cannot claim the USB interface\n$report")
        }
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
            if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
        }
        if (inEp == null) { conn.close(); throw IOException("no interrupt IN endpoint\n$report") }
        epIn = inEp
        epOut = outEp
        len = epIn.maxPacketSize.coerceIn(8, 64)
        probe()
    }

    /** Try the read methods on the header (address 0) and keep the first that works. */
    private fun probe() {
        val modes = if (Build.VERSION.SDK_INT >= 26) listOf(Recv.BULK, Recv.REQUEST) else listOf(Recv.BULK)
        for (out in listOf(false, true)) {
            if (out && epOut == null) continue
            useOutEp = out
            for (m in modes) {
                recvMode = m
                flush()
                try {
                    read32(0)
                    report.append("probe ${if (out) "OUT-ep" else "SET_REPORT"}/$m: ok\n")
                    return
                } catch (e: IOException) {
                    report.append("probe ${if (out) "OUT-ep" else "SET_REPORT"}/$m: ${e.message}\n")
                }
            }
        }
        close()
        throw IOException("no answer from the logger\n$report")
    }

    /** Drop reports the logger may still have queued. */
    private fun flush() {
        val b = ByteArray(len)
        repeat(8) { if (conn.bulkTransfer(epIn, b, len, 30) <= 0) return }
    }

    override fun send(report: IntArray) {
        val data = ByteArray(8) { report[it].toByte() }
        val n = if (useOutEp && epOut != null) {
            conn.bulkTransfer(epOut, data, 8, 1000)
        } else {
            // bmRequestType 0x21 (class, interface, host->device), SET_REPORT, output report id 0
            conn.controlTransfer(0x21, 0x09, 0x0200, intf.id, data, 8, 1000)
        }
        if (n < 0) throw IOException("USB write failed ($n)")
    }

    override fun recv(): IntArray {
        val b = when (recvMode) {
            Recv.BULK -> {
                val buf = ByteArray(len)
                val n = conn.bulkTransfer(epIn, buf, len, 1200)
                if (n < 8) throw IOException(if (n < 0) "no answer from the logger" else "short answer ($n B)")
                buf
            }
            Recv.REQUEST -> readRequest()
        }
        return IntArray(8) { b[it].toInt() and 0xFF }
    }

    private fun readRequest(): ByteArray {
        if (Build.VERSION.SDK_INT < 26) throw IOException("UsbRequest needs Android 8")
        val req = UsbRequest()
        try {
            if (!req.initialize(conn, epIn)) throw IOException("UsbRequest.initialize failed")
            val bb = ByteBuffer.allocate(len)
            if (!req.queue(bb)) throw IOException("UsbRequest.queue failed")
            try {
                conn.requestWait(1200) ?: throw IOException("UsbRequest failed")
            } catch (e: TimeoutException) {
                req.cancel()
                throw IOException("no answer from the logger (UsbRequest)")
            }
            if (bb.position() < 8) throw IOException("short answer (${bb.position()} B, UsbRequest)")
            return bb.array()
        } finally {
            req.close()
        }
    }

    override fun close() {
        try {
            conn.releaseInterface(intf)
        } catch (_: Exception) {
        }
        conn.close()
    }
}
