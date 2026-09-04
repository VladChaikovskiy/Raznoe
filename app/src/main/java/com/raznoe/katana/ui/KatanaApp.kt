package com.raznoe.katana.ui

import android.content.Intent
import android.hardware.usb.UsbDevice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raznoe.katana.DeviceInfo
import com.raznoe.katana.KatanaViewModel
import com.raznoe.katana.audio.JamOutput
import com.raznoe.katana.model.FactoryPresets
import com.raznoe.katana.model.MusicLibrary
import com.raznoe.katana.model.Patch
import com.raznoe.katana.model.Tracks
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.ParamKind
import kotlinx.coroutines.delay

private data class Block(val key: String, val short: String, val color: Color, val swId: String?)

private val CHAIN = listOf(
    Block("Noise Suppressor", "GATE", Nux.Gate, "ns_sw"),
    Block("Booster", "BST", Nux.Boost, "boost_sw"),
    Block("Усилитель", "AMP", Nux.Amp, null),
    Block("Mod", "MOD", Nux.Mod, "mod_sw"),
    Block("FX", "FX", Nux.Fx, "fx_sw"),
    Block("Delay", "DLY", Nux.Delay, "delay_sw"),
    Block("Reverb", "RVB", Nux.Reverb, "reverb_sw"),
)

@Composable
fun KatanaApp(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    var screen by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Nux.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                0 -> PatchScreen(vm, onConnectRequest)
                1 -> TogglesScreen(vm)
                2 -> PresetsScreen(vm)
                3 -> JamScreen(vm)
                else -> LibraryScreen(vm)
            }
        }
        BottomNav(screen) { screen = it }
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("Патч", "Тумблеры", "Пресеты", "Джем", "Библиотека")
    Row(
        Modifier.fillMaxWidth().background(Nux.Panel)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items.forEachIndexed { i, label ->
            val c = if (i == selected) Nux.Orange else Nux.TextLo
            Text(
                label, color = c, fontSize = 12.sp,
                fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { onSelect(i) }.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun DeviceTitle() {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            "K A T A N A",
            color = Nux.TextLo,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Text("by Vlad_i_c", color = Nux.Orange, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchScreen(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    var selectedBlock by remember { mutableIntStateOf(2) } // AMP
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DeviceTitle()
        CrashBanner(vm)
        ConnectionStrip(vm, onConnectRequest)

        // Channels
        Panel {
            Text("Каналы", color = Nux.TextLo, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KatanaParams.CHANNELS.forEachIndexed { i, (_, _) ->
                    RoundButton(if (i == 0) "P" else "$i", vm.currentChannel == i) {
                        vm.selectChannel(i)
                    }
                }
            }
        }

        // Signal chain
        Panel {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CHAIN.forEachIndexed { i, b ->
                    val on = b.swId?.let { (vm.paramValues[it] ?: 0) != 0 } ?: true
                    ChainChip(b, selected = i == selectedBlock, on = on) { selectedBlock = i }
                }
            }
        }

        BlockEditor(vm, CHAIN[selectedBlock])

        Text(
            "Gen 3: усилитель, вкл/выкл эффектов и параметры бустера/дилея/ревера/гейта " +
                "используют реальные адреса Gen 3 (эффекты — с учётом слота FX-BOX). " +
                "Параметры Mod/FX (тип и настройки) на Gen 3 устроены сложнее и пока в работе. " +
                "Что уходит в комбик — видно на вкладке «Лог».",
            color = Nux.TextLo, fontSize = 12.sp,
        )
    }
}

@Composable
private fun ChainChip(b: Block, selected: Boolean, on: Boolean, onClick: () -> Unit) {
    val border = if (selected) b.color else Nux.Stroke
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Nux.PanelHi)
                .border(2.dp, border, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(b.short, color = if (on) b.color else Nux.TextLo, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Box(
            Modifier.padding(top = 4.dp).size(7.dp).clip(RoundedCornerShape(4.dp))
                .background(if (on) b.color else Nux.Stroke),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockEditor(vm: KatanaViewModel, block: Block) {
    val params = KatanaParams.BY_CATEGORY[block.key].orEmpty()
    Panel(accent = block.color) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(block.key.uppercase(), color = block.color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            block.swId?.let { sw ->
                val on = (vm.paramValues[sw] ?: 0) != 0
                OnOffPills(on, block.color) { vm.setParam(KatanaParams.BY_ID[sw]!!, if (it) 1 else 0) }
            }
        }

        // enums (type selectors) and secondary toggles
        params.filter { it.id != block.swId }.forEach { p ->
            when (p.kind) {
                ParamKind.ENUM -> {
                    val v = vm.paramValues[p.id] ?: p.default
                    Text(p.label + mark(p), color = Nux.TextLo, fontSize = 13.sp)
                    ChipRow(p.options, p.indexOfValue(v), block.color) { idx ->
                        vm.setParam(p, p.valueOfIndex(idx))
                    }
                }
                ParamKind.TOGGLE -> {
                    val on = (vm.paramValues[p.id] ?: 0) != 0
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label + mark(p), color = Nux.TextHi)
                        OnOffPills(on, block.color) { vm.setParam(p, if (it) 1 else 0) }
                    }
                }
                ParamKind.CONTINUOUS -> {}
            }
        }

        // continuous params as knobs
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            params.filter { it.kind == ParamKind.CONTINUOUS }.forEach { p ->
                val v = vm.paramValues[p.id] ?: p.default
                Knob(p.label + mark(p), v, p.min, p.max, block.color) { vm.setParam(p, it) }
            }
        }
    }
}

private fun mark(p: KatanaParam) = if (!p.verified) " (?)" else ""

/**
 * Shown once after the app has gone down, with the trace ready to copy. The
 * phone is in a rehearsal room with no logcat attached, so this is the only way
 * a crash ever gets reported.
 */
@Composable
private fun CrashBanner(vm: KatanaViewModel) {
    val crash = vm.lastCrash ?: return
    val clipboard = LocalClipboardManager.current
    Panel(accent = Nux.Amp) {
        Text("⚠ В прошлый раз приложение упало", color = Nux.Amp, fontWeight = FontWeight.SemiBold)
        Text(
            crash.lineSequence().take(6).joinToString("\n"),
            color = Nux.TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Скопировать", selected = true, accent = Nux.Amp) {
                clipboard.setText(AnnotatedString(crash))
            }
            Pill("Скрыть", selected = false, accent = Nux.Amp) { vm.dismissCrashReport() }
        }
    }
}

@Composable
private fun ConnectionStrip(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    Panel {
        if (vm.connected) {
            Text("● ${vm.connectedLabel}", color = Nux.Orange, fontWeight = FontWeight.SemiBold)
            if (vm.identityInfo.isNotEmpty()) {
                Text("ID: ${vm.identityInfo}", color = Nux.TextLo, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Text(
                "Связь: TX ${vm.txCount} · RX ${vm.rxCount}" +
                    if (vm.gotData) "  ✓ данные идут" else "",
                color = if (vm.gotData) Nux.Gate else Nux.TextLo, fontSize = 12.sp,
            )
            if (vm.noResponse && !vm.gotData) {
                Text(
                    "⚠ Нет данных. 1) Включи комбик, УДЕРЖИВАЯ [BOOSTER] (режим USB-MIDI). " +
                        "2) Покрути любую ручку на комбике — приложение выучит заголовок Gen 3.",
                    color = Nux.Amp, fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Прочитать", selected = false, accent = Nux.Orange) { vm.readCurrentState() }
                Pill("Отключить", selected = true, accent = Nux.Orange) { vm.disconnect() }
            }
        } else {
            Text(
                "Включи Katana, удерживая [BOOSTER] (режим USB-MIDI), подключи кабель USB-C.",
                color = Nux.TextLo, fontSize = 12.sp,
            )
            Pill("Обновить список", selected = false, accent = Nux.Orange) { vm.refreshDevices() }
            vm.devices.forEach { d: DeviceInfo ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(d.label, color = Nux.TextHi, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Pill("Подключить", selected = true, accent = Nux.Orange) { onConnectRequest(d.device) }
                }
            }
        }
    }
}

@Composable
private fun TogglesScreen(vm: KatanaViewModel) {
    val toggles = KatanaParams.ALL.filter { it.kind == ParamKind.TOGGLE }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Text("Тумблеры", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Все переключатели вкл/выкл в одном месте (блоки эффектов, Solo, Noise Suppressor).",
            color = Nux.TextLo, fontSize = 12.sp,
        )
        Panel {
            toggles.forEach { p ->
                val on = (vm.paramValues[p.id] ?: 0) != 0
                val accent = blockColorFor(p.category)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label + mark(p), color = Nux.TextHi)
                        Text(p.category, color = Nux.TextLo, fontSize = 11.sp)
                    }
                    OnOffPills(on, accent) { vm.setParam(p, if (it) 1 else 0) }
                }
            }
        }
    }
}

private fun blockColorFor(category: String): Color = CHAIN.firstOrNull { it.key == category }?.color ?: Nux.Orange

@Composable
private fun PresetsScreen(vm: KatanaViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Text("Пресеты", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "★ — оригинальные демо-патчи BOSS/JuCaNeRy, остальные — мои версии. " +
                "Каждый пресет задаёт ВСЕ параметры сразу, поэтому от предыдущего тона " +
                "ничего не остаётся, и на каждом включён шумодав.",
            color = Nux.TextLo, fontSize = 12.sp,
        )
        if (vm.presetStatus.isNotEmpty()) {
            Text(
                vm.presetStatus,
                color = if (vm.presetLoading != null) Nux.Orange else Nux.Gate,
                fontSize = 12.sp,
            )
        }
        FactoryPresets.ALL.forEach { p ->
            val active = vm.activePreset == p.name
            val loading = vm.presetLoading == p.name
            Panel(accent = if (active) Nux.Gate else Nux.Stroke) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (active) "▶ " else "") + p.name,
                            color = if (active) Nux.Gate else Nux.TextHi,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (p.note.isNotEmpty()) Text(p.note, color = Nux.TextLo, fontSize = 11.sp)
                    }
                    val label = when {
                        loading -> "…"
                        active -> "Активен"
                        else -> "Загрузить"
                    }
                    Pill(label, selected = active || loading, accent = Nux.Orange) {
                        vm.applyPatch(p)
                    }
                }
            }
        }
    }
}

@Composable
private fun JamScreen(vm: KatanaViewModel) {
    val jam = vm.jam
    var search by remember { mutableStateOf("") }
    // Asking for the persistable grant is what makes a hand-picked file still
    // open after a restart; the stock OpenDocument contract does not.
    val picker = rememberLauncherForActivityResult(PickAudio()) { uris ->
        vm.addTracks(uris)
    }
    val musicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.scanLibrary(granted) }
    LaunchedEffect(jam.isPlaying) {
        while (jam.isPlaying) { jam.refreshPosition(); delay(400) }
    }
    // Whether a Bluetooth/USB output exists is queried from AudioManager rather
    // than held as state, so tick the screen to pick up a device that connects
    // while this tab is open.
    var routeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); routeTick++ } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header + add button
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Джем", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Pill("+ файл", selected = true, accent = Nux.Orange) { picker.launch(Unit) }
        }

        // Now playing: name, seek, transport with Play/Pause/Stop
        Panel(accent = Nux.Orange) {
            val name = jam.trackName.ifEmpty { "Ничего не выбрано" }
            Text(name, color = Nux.TextHi, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 13.sp)
            val dur = if (jam.durationMs > 0) jam.durationMs else 1
            Slider(
                value = jam.positionMs.coerceIn(0, dur).toFloat(),
                onValueChange = { jam.seekTo(it.toInt()) },
                valueRange = 0f..dur.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime(jam.positionMs), color = Nux.TextLo, fontSize = 11.sp)
                Text(fmtTime(jam.durationMs), color = Nux.TextLo, fontSize = 11.sp)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("▶", selected = jam.isPlaying, accent = Nux.Orange) {
                    if (!jam.isPlaying) jam.togglePlayPause()
                }
                Pill("⏸", selected = false, accent = Nux.Orange) {
                    if (jam.isPlaying) jam.togglePlayPause()
                }
                Pill("⏹", selected = false, accent = Nux.Orange) { jam.stop() }
                Pill("Луп", selected = jam.looping, accent = Nux.Orange) { jam.toggleLoop() }
                Pill("${jam.speed}x", selected = false, accent = Nux.Orange) { jam.cycleSpeed() }
            }
            if (jam.status.isNotEmpty()) {
                Text(
                    jam.status,
                    color = if (jam.waitingForRoute) Nux.Amp else Nux.TextLo,
                    fontSize = 11.sp,
                )
            }
        }

        // Where the backing track goes
        Panel(accent = if (jam.output == JamOutput.BLUETOOTH) Nux.Mod else Nux.Stroke) {
            Text("Куда играет минусовка", color = Nux.TextLo, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JamOutput.entries.forEach { o ->
                    Pill(o.label, selected = jam.output == o, accent = Nux.Orange) { jam.chooseOutput(o) }
                }
            }
            key(routeTick) {
                Text("Сейчас: ${jam.routeLabel}", color = Nux.TextHi, fontSize = 12.sp)
                // Only the routes that can reach the amp are worth naming; the
                // phone speaker is the fallback and needs no announcement.
                val external = jam.routes().filter { it.bluetooth || it.usb }
                if (external.isEmpty()) {
                    Text(
                        "Bluetooth и USB-аудио не подключены — минусовка пойдёт в телефон",
                        color = Nux.TextLo, fontSize = 11.sp,
                    )
                } else {
                    external.forEach { r ->
                        Text("• ${r.kind}: ${r.name}", color = Nux.TextLo, fontSize = 11.sp)
                    }
                }
            }
            Text(
                "Gen 3 умеет Bluetooth-аудио: подключи комбик в настройках Bluetooth телефона — " +
                    "минусовка пойдёт через его динамик, а пресеты продолжат идти по USB-кабелю. " +
                    "Если Bluetooth пропадёт, воспроизведение встанет на паузу и продолжится само.",
                color = Nux.TextLo, fontSize = 11.sp,
            )
        }

        // Volumes (compact)
        Panel {
            Text("MP3: ${(jam.volume * 100).toInt()}%", color = Nux.TextLo, fontSize = 12.sp)
            Slider(value = jam.volume, onValueChange = { jam.changeVolume(it) }, valueRange = 0f..1f)
            val gv = vm.paramValues[KatanaParams.VOLUME.id] ?: 0
            Text("Гитара: $gv", color = Nux.TextLo, fontSize = 12.sp)
            Slider(value = gv.toFloat(), onValueChange = { vm.setParam(KatanaParams.VOLUME, it.toInt()) },
                valueRange = 0f..100f)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Блокировать кнопки телефона", color = Nux.TextLo, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            OnOffPills(on = vm.lockHardwareKeys, accent = Nux.Orange) { vm.setKeyLock(it) }
        }

        // Access to the phone's music, then search, then the list itself.
        if (!vm.musicAccess) {
            Panel(accent = Nux.Amp) {
                Text(
                    "Дай доступ к музыке — и все треки с телефона появятся здесь сами.",
                    color = Nux.TextHi, fontSize = 12.sp,
                )
                Pill("Разрешить доступ к музыке", selected = true, accent = Nux.Orange) {
                    musicPermission.launch(MusicLibrary.permission())
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("поиск по названию / артисту") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Pill("⟳", selected = vm.scanningLibrary, accent = Nux.Orange) {
                vm.scanLibrary(vm.hasMusicPermission())
            }
        }

        val shown = Tracks.filter(vm.tracks, search)
        Text(
            if (vm.libraryStatus.isEmpty()) "Треков: ${shown.size}"
            else "${vm.libraryStatus} · показано: ${shown.size}",
            color = Nux.TextLo, fontSize = 11.sp,
        )

        // LazyColumn, not a scrolling Column: a phone library runs to hundreds
        // of tracks and composing every row at once makes the tab crawl.
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(shown, key = { it.uri }) { t ->
                val playing = jam.trackUri == t.uri
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clickable { vm.playTrack(t) },
                    ) {
                        Text(
                            (if (playing) "▶ " else "") + t.name,
                            color = if (playing) Nux.Orange else Nux.TextHi,
                            maxLines = 1, fontSize = 13.sp,
                        )
                        val sub = Tracks.subtitle(t)
                        if (sub.isNotEmpty()) {
                            Text(sub, color = Nux.TextLo, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                    // Only hand-picked files can be removed; a library track
                    // would just come back on the next scan.
                    if (!t.fromLibrary) {
                        Box(Modifier.padding(start = 8.dp)) {
                            Pill("✕", selected = false, accent = Nux.Amp) { vm.removeTrack(t) }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtTime(ms: Int): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun LibraryScreen(vm: KatanaViewModel) {
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Panel {
            Text("Сохранить текущий тон", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = name, onValueChange = { name = it },
                label = { Text("название") }, modifier = Modifier.fillMaxWidth())
            Pill("Сохранить", selected = true, accent = Nux.Orange) {
                if (name.isNotBlank()) { vm.capturePatch(name); name = "" }
            }
        }
        Panel {
            Text("Мои пресеты", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            if (vm.patches.isEmpty()) Text("Пока пусто.", color = Nux.TextLo, fontSize = 12.sp)
            vm.patches.forEach { patch: Patch ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(patch.name, color = Nux.TextHi, modifier = Modifier.weight(1f))
                    Pill("Загрузить", selected = false, accent = Nux.Orange) { vm.applyPatch(patch) }
                    Box(Modifier.padding(start = 8.dp)) {
                        Pill("✕", selected = false, accent = Nux.Amp) { vm.deletePatch(patch.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(accent: Color = Nux.Stroke, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Nux.Panel)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}
