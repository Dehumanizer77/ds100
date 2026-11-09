package sk.ds100.logger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import sk.ds100.logger.ui.App

class MainActivity : AppCompatActivity() {
    private val model: AppModel by viewModels()
    private lateinit var usb: UsbManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dev = intent.usbDevice() ?: return
            if (!dev.isDs100()) return
            when (intent.action) {
                ACTION_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(dev)
                    else model.message = Msg(R.string.usb_denied)
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    if (model.logger?.name == "USB") model.detach()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { App(model) }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!model.connected) findAndOpen()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.usbDevice()?.takeIf { it.isDs100() }?.let { open(it) }
        }
    }

    /** Look for an already plugged-in logger; ask for permission if needed. */
    fun findAndOpen() {
        if (model.connected) return
        val dev = usb.deviceList.values.firstOrNull { it.isDs100() } ?: return
        if (usb.hasPermission(dev)) {
            open(dev)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags)
            usb.requestPermission(dev, pi)
        }
    }

    private fun open(dev: UsbDevice) {
        if (model.connected && model.logger?.name == "USB") return
        if (!usb.hasPermission(dev)) { findAndOpen(); return }
        model.attachAsync { UsbTransport(usb, dev) }
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        const val ACTION_PERMISSION = "sk.ds100.logger.USB_PERMISSION"
    }
}
