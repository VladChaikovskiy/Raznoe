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
import com.raznoe.katana.protocol.ParamKind
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
    var status by mutableStateOf("Не подключено")
        private set
    var connected by mutableStateOf(false)
        private set
    var connectedLabel by mutableStateOf("")
        private set
    var identityInfo by mutableStateOf("")
        private set
    var currentChannel by mutableStateOf(0)
        private set

    val devices = mutableStateListOf<DeviceInfo>()
    val paramValues = mutableStateMapOf<String, Int>()
    val log = mutableStateListOf<String>()
    val patches = mutableStateListOf<Patch>()

    init {
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
        if (devices.isEmpty()) {
            status = "Устройство Roland не найдено. Включи комбик, удерживая [BOOSTER], и подключи кабель."
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun connect(device: UsbDevice) {
        disconnect()
        val conn = UsbMidiConnection(usbManager, device)
        val ctl = KatanaController(conn)
        ctl.onTraffic = { dir, sysex -> appendLog("$dir  ${KatanaSysEx.toHex(sysex)}") }
        ctl.onIncoming = { incoming -> onMain { applyIncoming(incoming) } }
        ctl.onIdentity = { bytes ->
            val hex = bytes.joinToString(" ") { "%02X".format(it) }
            onMain {
                identityInfo = hex
                appendLog("IDENTITY  $hex")
            }
        }

        val error = conn.open(onSysEx = { sysex -> ctl.handleInbound(sysex) })
        if (error != null) {
            status = "Не удалось подключиться: $error"
            connected = false
            return
        }
        connection = conn
        controller = ctl
        connected = true
        connectedLabel = device.productName ?: "BOSS Katana"
        status = "Подключено: $connectedLabel"
        appendLog("— подключено, рукопожатие + чтение состояния —")
        ctl.begin()
    }

    fun disconnect() {
        controller?.shutdown()
        controller = null
        connection?.close()
        connection = null
        if (connected) appendLog("— отключено —")
        connected = false
        connectedLabel = ""
        status = "Не подключено"
    }

    // --- Controls ---------------------------------------------------------
    fun setParam(param: KatanaParam, value: Int) {
        val stored = if (param.kind == ParamKind.ENUM) value else value.coerceIn(param.min, param.max)
        paramValues[param.id] = stored
        controller?.setParam(param, stored)
    }

    fun selectChannel(index: Int) {
        val (_, dataByte) = KatanaParams.CHANNELS.getOrElse(index) { return }
        currentChannel = index
        controller?.selectChannel(dataByte)
    }

    fun readCurrentState() {
        controller?.readAll()
    }

    fun sendRawHex(hex: String): String {
        val ctl = controller ?: return "Не подключено"
        return try {
            val bytes = KatanaSysEx.fromHex(hex)
            if (bytes.isEmpty()) return "Нечего отправлять"
            ctl.sendSysEx(bytes)
            "Отправлено ${bytes.size} байт"
        } catch (e: Exception) {
            "Ошибка hex: ${e.message}"
        }
    }

    fun readBlockHex(addressHex: String, size: Int): String {
        val ctl = controller ?: return "Не подключено"
        return try {
            val addr = KatanaSysEx.fromHex(addressHex).map { it.toInt() and 0xFF }.toIntArray()
            if (addr.size != 4) return "Адрес должен быть из 4 байт"
            ctl.readBlock(addr, size)
            "Запрошено $size байт"
        } catch (e: Exception) {
            "Ошибка адреса: ${e.message}"
        }
    }

    /**
     * Fold an inbound DT1 (which may be a whole block from an RQ1 read or a
     * single-parameter knob echo) back onto our parameter values. For each
     * param whose address falls inside the reported block, pull its byte(s).
     */
    private fun applyIncoming(incoming: KatanaSysEx.Incoming) {
        val addr = incoming.address
        val data = incoming.data
        if (data.isEmpty()) return
        for (p in KatanaParams.ALL) {
            if (p.address[0] != addr[0] || p.address[1] != addr[1] || p.address[2] != addr[2]) continue
            val offset = p.address[3] - addr[3]
            if (offset < 0 || offset >= data.size) continue
            val value = if (p.word && offset + 1 < data.size) {
                ((data[offset] and 0x7F) shl 7) or (data[offset + 1] and 0x7F)
            } else {
                data[offset] and 0x7F
            }
            paramValues[p.id] = value
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
        appendLog("— применён пресет '${patch.name}' —")
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
