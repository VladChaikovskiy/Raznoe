package com.raznoe.katana

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.raznoe.katana.ui.KatanaApp
import com.raznoe.katana.ui.KatanaTheme

class MainActivity : ComponentActivity() {

    private val vm: KatanaViewModel by viewModels()
    private lateinit var usbManager: UsbManager

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDevice() ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) vm.connect(device)
            else vm.disconnect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            this, permissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            KatanaTheme {
                KatanaApp(
                    vm = vm,
                    onConnectRequest = ::requestConnect,
                )
            }
        }

        askForNotifications()
        handleAttachIntent(intent)
    }

    /**
     * The playback notification is what lets the backing track keep going with
     * the app off screen (Android 13+ needs permission to post it). Denying it
     * only costs the transport controls — playback itself still works.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAttachIntent(intent)
    }

    /**
     * When the "lock phone buttons" jam option is on, swallow hardware volume
     * and media/headset key events. These are what a 4-pole (TRRS) AUX cable +
     * USB ground loop injects as phantom presses; consuming them here stops the
     * random volume changes and stray media-button actions while jamming.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.lockHardwareKeys && event.keyCode in NUISANCE_KEYS) return true
        return super.dispatchKeyEvent(event)
    }

    /** Auto-connect when the amp is plugged in and launches us. */
    private fun handleAttachIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            vm.refreshDevices()
            intent.usbDevice()?.let { requestConnect(it) }
        }
    }

    /** Ask for USB permission if needed, then connect. */
    private fun requestConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            vm.connect(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags,
            )
            usbManager.requestPermission(device, pi)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        const val ACTION_USB_PERMISSION = "com.raznoe.katana.USB_PERMISSION"
        private val NUISANCE_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
        )
    }
}
