package com.raznoe.katana

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.raznoe.katana.audio.Jam
import com.raznoe.katana.audio.JamPlayer
import com.raznoe.katana.model.FactoryPresets
import com.raznoe.katana.model.Patch
import com.raznoe.katana.model.PatchStore
import com.raznoe.katana.model.Track
import com.raznoe.katana.model.TrackStore
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx
import com.raznoe.katana.protocol.ParamKind
import com.raznoe.katana.usb.KatanaController
import com.raznoe.katana.usb.UsbMidiConnection
import java.util.concurrent.atomic.AtomicInteger

/** Roland Corporation USB vendor id. */
const val ROLAND_VENDOR_ID = 0x0582

data class DeviceInfo(val device: UsbDevice, val label: String)

class KatanaViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val patchStore = PatchStore(app)
    private val trackStore = TrackStore(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connection: UsbMidiConnection? = null
    private var controller: KatanaController? = null

    /** Backing-track player. Application-scoped so playback outlives the UI. */
    val jam: JamPlayer = Jam.get(app)

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
    var txCount by mutableStateOf(0)
        private set
    var rxCount by mutableStateOf(0)
        private set
    var noResponse by mutableStateOf(false)
        private set
    var gotData by mutableStateOf(false)
        private set
    /** Name of the preset currently being written to the amp, if any. */
    var presetLoading by mutableStateOf<String?>(null)
        private set
    /** Result of the last preset load, for the Пресеты screen. */
    var presetStatus by mutableStateOf("")
        private set
    /** Stack trace of the last crash, so a field failure can be reported. */
    var lastCrash by mutableStateOf<String?>(null)
        private set

    val devices = mutableStateListOf<DeviceInfo>()
    val paramValues = mutableStateMapOf<String, Int>()
    // Diagnostic logs: plain, capped, thread-safe — NOT Compose state. Nothing
    // in the UI reads them, so per-packet snapshot writes were pure main-thread
    // load (the cause of "works then freezes"). appendLog/logAction never touch
    // the main thread now.
    private val log = ArrayDeque<String>()
    private val actionLog = ArrayDeque<String>()
    // TX/RX counters are hit on the USB threads for every packet; increment
    // lock-free and push to Compose state at most a few times/sec.
    private val txAtomic = AtomicInteger(0)
    private val rxAtomic = AtomicInteger(0)
    @Volatile private var countsPostScheduled = false
    val patches = mutableStateListOf<Patch>()

    // --- Jam player state -------------------------------------------------
    val tracks = mutableStateListOf<Track>()
    var activePreset by mutableStateOf("")
        private set
    /**
     * true => the Activity swallows hardware volume/media key events. Guards
     * against phantom key presses induced by ground-loop noise when an analog
     * AUX cable and USB are connected at the same time.
     */
    var lockHardwareKeys by mutableStateOf(false)
        private set

    fun setKeyLock(on: Boolean) { lockHardwareKeys = on }

    init {
        KatanaParams.ALL.forEach { paramValues[it.id] = it.default }
        refreshDevices()
        refreshPatches()
        tracks.addAll(trackStore.list())
        jam.onLog = { line -> logAction("", line) }
        lastCrash = KatanaApplication.lastCrash(app)
    }

    fun dismissCrashReport() {
        KatanaApplication.clearCrash(app)
        lastCrash = null
    }

    /** The track currently loaded in the player, as an index into [tracks]. */
    val currentTrack: Int?
        get() = jam.trackUri?.let { uri -> tracks.indexOfFirst { it.uri == uri }.takeIf { it >= 0 } }

    // --- Device discovery / connection -----------------------------------
    fun refreshDevices() {
        devices.clear()
        val found = runCatching { usbManager.deviceList.values.toList() }.getOrDefault(emptyList())
        found.filter { it.vendorId == ROLAND_VENDOR_ID }
            .forEach {
                devices.add(
                    DeviceInfo(
                        it,
                        "${it.productName ?: "Roland/BOSS"}  " +
                            "(VID %04X / PID %04X)".format(it.vendorId, it.productId),
                    ),
                )
            }
        if (devices.isEmpty() && !connected) {
            status = "Устройство Roland не найдено. Включи комбик, удерживая [BOOSTER], и подключи кабель."
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    // --- Auto-reconnect ---------------------------------------------------
    // A USB-C phone connector under a guitar cable's worth of tugging drops the
    // link regularly, and the app used to just sit there saying "отключён"
    // until you noticed and re-tapped Подключить. Now it comes back by itself.
    private var lastDevice: UsbDevice? = null
    private var autoReconnect = true
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { attemptReconnect() }
    private val noResponseRunnable = Runnable {
        if (connected && !gotData) {
            noResponse = true
            appendLog(
                "⚠ Нет данных от комбика. 1) Проверь, что он включён с зажатым [BOOSTER] " +
                    "(режим USB-MIDI). 2) Покрути любую ручку на самом комбике — приложение " +
                    "выучит правильный заголовок Gen 3 из его ответа.",
            )
        }
    }

    fun connect(device: UsbDevice) {
        teardown()
        autoReconnect = true
        lastDevice = device
        val conn = UsbMidiConnection(usbManager, device)
        val ctl = KatanaController(conn)
        ctl.onTraffic = { dir, sysex ->
            bumpCount(dir == "RX")
            appendLog("$dir  ${KatanaSysEx.toHex(sysex)}")
        }
        ctl.onIncoming = { incoming ->
            onMain {
                if (!gotData) { gotData = true; reconnectAttempts = 0 }
                applyIncoming(incoming)
            }
        }
        ctl.onInfo = { msg -> appendLog(msg) }
        ctl.onSelectors = { sel ->
            logAction("gen3sel", "Gen3 FX-BOX слоты: booster=${sel[0]} mod=${sel[1]} " +
                "fx=${sel[2]} delay=${sel[3]} reverb=${sel[4]}")
        }
        ctl.onIdentity = { bytes ->
            val hex = bytes.joinToString(" ") { "%02X".format(it) }
            onMain {
                identityInfo = hex
                appendLog("IDENTITY  $hex")
            }
        }

        conn.onDisconnect = {
            // Reader thread died (USB glitch/unplug). Tear the connection down
            // FULLY off the main thread, so we don't leave a half-dead pipe
            // (sender alive, reader dead, interface still claimed) that would
            // make the app look "stuck" and block a future reconnect.
            onMain {
                if (connection === conn) {
                    val dead = controller
                    controller = null; connection = null
                    connected = false
                    status = "Комбик отключён (проверь кабель)"
                    appendLog("— соединение потеряно —")
                    Thread { runCatching { dead?.shutdown() }; runCatching { conn.close() } }
                        .apply { isDaemon = true }.start()
                    scheduleReconnect()
                }
            }
        }
        val error = conn.open(onSysEx = { sysex -> ctl.handleInbound(sysex) })
        if (error != null) {
            status = "Не удалось подключиться: $error"
            connected = false
            scheduleReconnect()
            return
        }
        connection = conn
        controller = ctl
        connected = true
        connectedLabel = device.productName ?: "BOSS Katana"
        status = "Подключено: $connectedLabel"
        txAtomic.set(0); rxAtomic.set(0); txCount = 0; rxCount = 0
        noResponse = false; gotData = false
        appendLog("— подключено (PID %04X) —".format(device.productId))
        appendLog("USB: ${conn.diagnostics}")
        appendLog("— рукопожатие + автоопределение диалекта —")
        ctl.begin()
        // After the handshake, if we still have no real data the amp is either
        // not in USB-MIDI mode or needs a physical knob nudge.
        mainHandler.removeCallbacks(noResponseRunnable)
        mainHandler.postDelayed(noResponseRunnable, NO_RESPONSE_MS)
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        if (lastDevice == null) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            status = "Связь потеряна. Проверь кабель и нажми «Обновить список»."
            return
        }
        reconnectAttempts++
        val delay = RECONNECT_STEP_MS * reconnectAttempts
        status = "Переподключение через ${delay / 1000} с (попытка $reconnectAttempts)…"
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun attemptReconnect() {
        if (!autoReconnect || connected) return
        val wanted = lastDevice ?: return
        refreshDevices()
        // Re-plugging gives the amp a new UsbDevice instance, so match on the
        // stable device name rather than on object identity.
        val fresh = devices.firstOrNull { it.device.deviceName == wanted.deviceName }?.device
        when {
            fresh == null -> scheduleReconnect()
            !usbManager.hasPermission(fresh) -> {
                status = "Комбик найден, но нужно разрешение USB — нажми «Подключить»."
                appendLog("— переподключение: нет разрешения USB —")
            }
            else -> {
                appendLog("— автопереподключение (попытка $reconnectAttempts) —")
                connect(fresh)
            }
        }
    }

    /** User-initiated disconnect: stop trying to come back. */
    fun disconnect() {
        autoReconnect = false
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        val was = connected
        teardown()
        if (was) appendLog("— отключено —")
        status = "Не подключено"
    }

    /** Close the link without touching the auto-reconnect decision. */
    private fun teardown() {
        mainHandler.removeCallbacks(noResponseRunnable)
        controller?.shutdown()
        controller = null
        connection?.close()
        connection = null
        connected = false
        connectedLabel = ""
    }

    // --- Action log (what the user pressed, address used, sent or not) ----
    private var lastActionKey = ""

    private fun logAction(key: String, text: String) = synchronized(actionLog) {
        if (key.isNotEmpty() && key == lastActionKey && actionLog.isNotEmpty()) {
            actionLog[actionLog.size - 1] = text
        } else {
            actionLog.addLast(text)
            while (actionLog.size > 600) actionLog.removeFirst()
        }
        lastActionKey = key
    }

    private fun addrHex(a: IntArray) = a.joinToString(" ") { "%02X".format(it and 0xFF) }

    fun clearActionLog() = synchronized(actionLog) { actionLog.clear(); lastActionKey = "" }

    fun actionLogText(): String = buildString {
        append("$APP_NAME — журнал действий\n")
        append("Подключено: $connected ($connectedLabel)  ID: ${identityInfo.ifEmpty { "—" }}\n")
        append("Профиль: ${KatanaSysEx.generation}  заголовок ${KatanaSysEx.headerHex()}\n")
        append("TX=${txAtomic.get()} RX=${rxAtomic.get()}\n----\n")
        append(synchronized(actionLog) { actionLog.joinToString("\n") })
    }

    /** Push USB-thread packet counters to Compose state a few times/sec. */
    private fun bumpCount(rx: Boolean) {
        if (rx) rxAtomic.incrementAndGet() else txAtomic.incrementAndGet()
        if (!countsPostScheduled) {
            countsPostScheduled = true
            mainHandler.postDelayed({
                countsPostScheduled = false
                txCount = txAtomic.get(); rxCount = rxAtomic.get()
            }, 300)
        }
    }

    // --- Controls ---------------------------------------------------------
    fun setParam(param: KatanaParam, value: Int) {
        val stored = KatanaParams.sanitize(param, value)
        paramValues[param.id] = stored
        controller?.setParam(param, stored)
        val gen3 = KatanaSysEx.generation == KatanaSysEx.Gen.GEN3
        val valTxt = when (param.kind) {
            ParamKind.TOGGLE -> if (stored != 0) "ON" else "OFF"
            ParamKind.ENUM -> param.options.getOrElse(param.indexOfValue(stored)) { "$stored" }
            ParamKind.CONTINUOUS -> "$stored"
        }
        val ctl = controller
        val where = if (ctl == null) "(нет связи)"
        else "→ ${addrHex(ctl.resolveAddress(param))}${if (gen3) " G3" else ""}"
        logAction(param.id, "${param.category}/${param.label}: $valTxt  $where")
    }

    fun selectChannel(index: Int) {
        val (name, dataByte) = KatanaParams.CHANNELS.getOrElse(index) { return }
        currentChannel = index
        controller?.selectChannel(dataByte)
        logAction("channel", "Канал → $name  ${if (controller == null) "(нет связи)" else "→ 00 01 00 00"}")
    }

    fun readCurrentState() {
        controller?.readAll()
    }

    /** Experimental new-gen (Gen 3 / Katana:GO dialect) amp command from the console. */
    fun sendNewGenAmp(knob: Int, value: Int): String {
        val ctl = controller ?: return "Не подключено"
        val frame = KatanaSysEx.buildNewGenAmpSet(knob, value)
        ctl.sendSysEx(frame)
        return "→ ${KatanaSysEx.toHex(frame)}"
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
            // Match against both the MkII and Gen 3 address of the parameter.
            for (pa in listOfNotNull(p.address, p.addrGen3)) {
                if (pa[0] != addr[0] || pa[1] != addr[1] || pa[2] != addr[2]) continue
                val offset = pa[3] - addr[3]
                if (offset < 0 || offset >= data.size) continue
                val v = if (p.word && offset + 1 < data.size) {
                    ((data[offset] and 0x7F) shl 7) or (data[offset + 1] and 0x7F)
                } else {
                    data[offset] and 0x7F
                }
                // Skip no-op writes: the amp echoes back every value we send, so
                // most inbound frames match what we already have — updating the
                // snapshot map anyway would trigger needless recomposition.
                if (paramValues[p.id] != v) paramValues[p.id] = v
            }
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

    /**
     * Load a patch: knobs and types first, on/off switches last, as one atomic
     * batch (see [KatanaController.applyPreset]). The whole parameter set is
     * always sent — a patch never inherits anything from the previous tone.
     */
    fun applyPatch(patch: Patch) {
        val entries = FactoryPresets.loadOrder(patch.values)
        // Reflect it locally first so the UI is right even with no cable.
        entries.forEach { (p, v) -> paramValues[p.id] = v }
        activePreset = patch.name
        val ctl = controller
        val missing = KatanaParams.ALL.size - entries.size
        if (ctl == null) {
            presetStatus = "«${patch.name}»: нет связи — значения только в приложении"
            logAction("", "Пресет '${patch.name}': нет связи (${entries.size} параметров локально)")
            return
        }
        presetLoading = patch.name
        presetStatus = "Загружаю «${patch.name}»…"
        appendLog("— применяю пресет '${patch.name}' (${entries.size} параметров) —")
        ctl.applyPreset(entries) { sent, total ->
            onMain {
                presetLoading = null
                presetStatus = if (sent == total) {
                    "Загружен: «${patch.name}» ($sent сообщений)"
                } else {
                    "«${patch.name}» прерван на $sent из $total — загрузи ещё раз"
                }
                logAction("", "Пресет '${patch.name}': отправлено $sent из $total сообщений" +
                    if (missing > 0) " (нет значений для $missing параметров)" else "")
            }
        }
    }

    fun deletePatch(name: String) {
        patchStore.delete(name)
        refreshPatches()
    }

    // --- Jam player (MP3 backing tracks) ---------------------------------
    fun addTrack(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "track"
        if (tracks.none { it.uri == uri.toString() }) {
            tracks.add(Track(uri.toString(), name))
            runCatching { trackStore.saveAll(tracks.toList()) }
        }
        logAction("", "Джем: добавлен трек «$name»")
    }

    fun removeTrack(index: Int) {
        val t = tracks.getOrNull(index) ?: return
        if (jam.trackUri == t.uri) jam.stop()
        tracks.removeAt(index)
        runCatching { trackStore.saveAll(tracks.toList()) }
    }

    fun playTrack(index: Int) {
        val t = tracks.getOrNull(index) ?: return
        jam.play(t.uri, t.name)
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    // --- Log --------------------------------------------------------------
    fun clearLog() = synchronized(log) { log.clear() }

    /** Full log as shareable text (for sending diagnostics). */
    fun logText(): String = buildString {
        append("$APP_NAME — диагностика\n")
        append("Подключено: $connected  ($connectedLabel)\n")
        append("ID: ${identityInfo.ifEmpty { "—" }}\n")
        append("TX=${txAtomic.get()}  RX=${rxAtomic.get()}  noResponse=$noResponse\n")
        append("Профиль modelId=0x%02X\n".format(KatanaSysEx.modelId))
        append("Звук: выход=${jam.output.label} маршрут=${jam.routeLabel}\n")
        lastCrash?.let { append("--- последний сбой ---\n$it\n") }
        append("----\n")
        append(synchronized(log) { log.joinToString("\n") })
    }

    // Off the main thread, cheap, capped — called for every USB packet.
    private fun appendLog(line: String) = synchronized(log) {
        log.addLast(line)
        while (log.size > MAX_LOG_LINES) log.removeFirst()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    override fun onCleared() {
        // The player deliberately outlives the ViewModel: the Activity being
        // recreated (rotation, USB attach intent) must not cut the track off.
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(noResponseRunnable)
        autoReconnect = false
        teardown()
        super.onCleared()
    }

    companion object {
        const val APP_NAME = "Katana by Vlad_i_c"
        private const val MAX_LOG_LINES = 400
        private const val NO_RESPONSE_MS = 6_000L
        private const val RECONNECT_STEP_MS = 1_500L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
