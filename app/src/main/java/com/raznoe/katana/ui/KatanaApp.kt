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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.raznoe.katana.model.FactoryPresets
import com.raznoe.katana.model.Patch
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
    Text(
        "K A T A N A",
        color = Nux.TextLo,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    )
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
        Text("Пресеты (мои версии)", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Стартовые тоны в духе известных названий. Это мои версии, не оригинальные JNs. " +
                "Нажми «Загрузить» — параметры уйдут на комбик.",
            color = Nux.TextLo, fontSize = 12.sp,
        )
        FactoryPresets.ALL.forEach { p ->
            val active = vm.activePreset == p.name
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
                    Pill(if (active) "Активен" else "Загрузить", selected = active, accent = Nux.Orange) {
                        vm.applyPatch(p)
                    }
                }
            }
        }
    }
}

@Composable
private fun JamScreen(vm: KatanaViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addTrack(it) }
    }
    LaunchedEffect(vm.isPlaying) {
        while (vm.isPlaying) { vm.refreshPosition(); delay(400) }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header + add button
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Джем", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Pill("+ MP3", selected = true, accent = Nux.Orange) { picker.launch(arrayOf("audio/*")) }
        }

        // Now playing: name, seek, transport with Play/Pause/Stop
        Panel(accent = Nux.Orange) {
            val idx = vm.currentTrack
            val name = idx?.let { vm.tracks.getOrNull(it)?.name } ?: "Ничего не выбрано"
            Text(name, color = Nux.TextHi, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 13.sp)
            val dur = if (vm.durationMs > 0) vm.durationMs else 1
            Slider(
                value = vm.positionMs.coerceIn(0, dur).toFloat(),
                onValueChange = { vm.seekTo(it.toInt()) },
                valueRange = 0f..dur.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime(vm.positionMs), color = Nux.TextLo, fontSize = 11.sp)
                Text(fmtTime(vm.durationMs), color = Nux.TextLo, fontSize = 11.sp)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("▶", selected = vm.isPlaying, accent = Nux.Orange) {
                    if (!vm.isPlaying) vm.togglePlayPause()
                }
                Pill("⏸", selected = false, accent = Nux.Orange) {
                    if (vm.isPlaying) vm.togglePlayPause()
                }
                Pill("⏹", selected = false, accent = Nux.Orange) { vm.stopPlayback() }
                Pill("Луп", selected = vm.looping, accent = Nux.Orange) { vm.toggleLoop() }
                Pill("${vm.speed}x", selected = false, accent = Nux.Orange) { vm.cycleSpeed() }
            }
        }

        // Volumes (compact)
        Panel {
            Text("MP3: ${(vm.mp3Volume * 100).toInt()}%", color = Nux.TextLo, fontSize = 12.sp)
            Slider(value = vm.mp3Volume, onValueChange = { vm.changeMp3Volume(it) }, valueRange = 0f..1f)
            val gv = vm.paramValues[KatanaParams.VOLUME.id] ?: 0
            Text("Гитара: $gv", color = Nux.TextLo, fontSize = 12.sp)
            Slider(value = gv.toFloat(), onValueChange = { vm.setParam(KatanaParams.VOLUME, it.toInt()) },
                valueRange = 0f..100f)
        }

        // Output + key-lock (compact, one row each)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Pill("Комбик", selected = vm.jamThroughAmp, accent = Nux.Orange) { vm.chooseJamOutput(true) }
            Pill("Телефон", selected = !vm.jamThroughAmp, accent = Nux.Orange) { vm.chooseJamOutput(false) }
            Text(if (vm.ampAudioAvailable()) "✓" else "✗",
                color = if (vm.ampAudioAvailable()) Nux.Orange else Nux.Pink, fontSize = 16.sp)
            Box(Modifier.weight(1f))
            Text("Кнопки", color = Nux.TextLo, fontSize = 12.sp)
            OnOffPills(on = vm.lockHardwareKeys, accent = Nux.Orange) { vm.setKeyLock(it) }
        }

        // Track list fills the rest and scrolls internally
        if (vm.tracks.isEmpty()) {
            Text("Пусто — жми «+ MP3».", color = Nux.TextLo, fontSize = 12.sp)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            vm.tracks.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.name,
                        color = if (vm.currentTrack == i) Nux.Orange else Nux.TextHi,
                        modifier = Modifier.weight(1f).clickable { vm.playTrack(i) },
                        maxLines = 1, fontSize = 13.sp,
                    )
                    Pill("▶", selected = false, accent = Nux.Orange) { vm.playTrack(i) }
                    Box(Modifier.padding(start = 8.dp)) {
                        Pill("✕", selected = false, accent = Nux.Amp) { vm.removeTrack(i) }
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
