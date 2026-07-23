package com.raznoe.katana

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.raznoe.katana.model.Patch
import com.raznoe.katana.model.PatchStore
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx
import com.raznoe.katana.usb.KatanaController
import com.raznoe.katana.usb.UsbMidiConnection

/** Roland Corporation USB vendor id. */
const val ROLAND_VENDOR_ID = 0x0582

data class DeviceInfo(val device: UsbDevice, val label: String)

class KatanaViewModel(app: Application) : AndroidViewModel(app) {

    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val patchStore = PatchStore(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connection: UsbMidiConnection? = null
    private var controller: KatanaController? = null

    // --- UI state ---------------------------------------------------------
    var status by mutableStateOf("Not connected")
        private set
    var connected by mutableStateOf(false)
        private set
    var connectedLabel by mutableStateOf("")
        private set

    val devices = mutableStateListOf<DeviceInfo>()
    val paramValues = mutableStateMapOf<String, Int>()
    val log = mutableStateListOf<String>()
    val patches = mutableStateListOf<Patch>()

    init {
        // seed defaults so sliders have a position before the amp reports back
        KatanaParams.ALL.forEach { paramValues[it.id] = it.default }
        refreshDevices()
        refreshPatches()
    }

    // --- Device discovery / connection -----------------------------------
    fun refreshDevices() {
        devices.clear()
        usbManager.deviceList.values
            .filter { it.vendorId == ROLAND_VENDOR_ID }
            .forEach {
                devices.add(
                    DeviceInfo(
                        it,
                        "${it.productName ?: "Roland/BOSS"}  " +
                            "(VID %04X / PID %04X)".format(it.vendorId, it.productId),
                    ),
                )
            }
        if (devices.isEmpty()) status = "No Roland/BOSS device found. Plug in the Katana."
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun connect(device: UsbDevice) {
        disconnect()
        val conn = UsbMidiConnection(usbManager, device)
        val ctl = KatanaController(conn)
        ctl.onTraffic = { dir, sysex -> appendLog("$dir  ${KatanaSysEx.toHex(sysex)}") }
        ctl.onIncoming = { incoming -> onMain { applyIncoming(incoming) } }

        val error = conn.open(onSysEx = { sysex -> ctl.handleInbound(sysex) })
        if (error != null) {
            status = "Connect failed: $error"
            connected = false
            return
        }
        connection = conn
        controller = ctl
        connected = true
        connectedLabel = device.productName ?: "BOSS Katana"
        status = "Connected to $connectedLabel"
        appendLog("— connected, requesting current state —")
        ctl.readTempPatch()
    }

    fun disconnect() {
        controller = null
        connection?.close()
        connection = null
        if (connected) appendLog("— disconnected —")
        connected = false
        connectedLabel = ""
        status = "Not connected"
    }

    // --- Controls ---------------------------------------------------------
    fun setParam(param: KatanaParam, value: Int) {
        paramValues[param.id] = value.coerceIn(param.min, param.max)
        controller?.setParam(param, value)
    }

    fun selectProgram(program: Int) {
        controller?.selectProgram(program)
    }

    fun readCurrentState() {
        controller?.readTempPatch()
    }

    /** Send a raw SysEx typed as hex in the console. */
    fun sendRawHex(hex: String): String {
        val ctl = controller ?: return "Not connected"
        return try {
            val bytes = KatanaSysEx.fromHex(hex)
            if (bytes.isEmpty()) return "Nothing to send"
            ctl.sendSysEx(bytes)
            "Sent ${bytes.size} bytes"
        } catch (e: Exception) {
            "Bad hex: ${e.message}"
        }
    }

    fun readBlockHex(addressHex: String, size: Int): String {
        val ctl = controller ?: return "Not connected"
        return try {
            val addr = KatanaSysEx.fromHex(addressHex).map { it.toInt() and 0xFF }.toIntArray()
            if (addr.size != 4) return "Address must be 4 bytes"
            ctl.readBlock(addr, size)
            "Requested $size bytes"
        } catch (e: Exception) {
            "Bad address: ${e.message}"
        }
    }

    private fun applyIncoming(incoming: KatanaSysEx.Incoming) {
        // Map a reported single-byte value back onto a known parameter.
        KatanaParams.ALL.firstOrNull { it.address.contentEquals(incoming.address) }?.let { p ->
            if (incoming.data.isNotEmpty()) paramValues[p.id] = incoming.data[0] and 0x7F
        }
    }

    // --- Patches (librarian) ---------------------------------------------
    fun refreshPatches() {
        patches.clear()
        patches.addAll(patchStore.list())
    }

    fun capturePatch(name: String) {
        val snapshot = KatanaParams.ALL.associate { it.id to (paramValues[it.id] ?: it.default) }
        patchStore.save(Patch(name = name, values = snapshot))
        refreshPatches()
    }

    fun applyPatch(patch: Patch) {
        patch.values.forEach { (id, value) ->
            KatanaParams.BY_ID[id]?.let { setParam(it, value) }
        }
        appendLog("— applied patch '${patch.name}' —")
    }

    fun deletePatch(name: String) {
        patchStore.delete(name)
        refreshPatches()
    }

    // --- Log --------------------------------------------------------------
    fun clearLog() = log.clear()

    private fun appendLog(line: String) = onMain {
        log.add(line)
        if (log.size > MAX_LOG_LINES) log.removeAt(0)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    companion object {
        private const val MAX_LOG_LINES = 400
    }
}
