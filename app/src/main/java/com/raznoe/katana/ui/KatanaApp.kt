package com.raznoe.katana.ui

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raznoe.katana.DeviceInfo
import com.raznoe.katana.KatanaViewModel
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.ParamKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KatanaApp(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Управление", "Консоль", "Библиотека")

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Katana Ctl", style = MaterialTheme.typography.titleLarge)
                    Text(
                        vm.status,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBar(vm, onConnectRequest)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> ControlTab(vm)
                1 -> ConsoleTab(vm)
                else -> LibraryTab(vm)
            }
        }
    }
}

@Composable
private fun ConnectionBar(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.connected) {
                Text("● ${vm.connectedLabel}", color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.readCurrentState() }) { Text("Прочитать состояние") }
                    Button(onClick = { vm.disconnect() }) { Text("Отключить") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.refreshDevices() }) { Text("Обновить список") }
                }
                if (vm.devices.isEmpty()) {
                    Text(
                        "Подключи Katana к телефону кабелем USB-C и разреши доступ.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                vm.devices.forEach { d: DeviceInfo ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            d.label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        Button(onClick = { onConnectRequest(d.device) }) { Text("Подключить") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlTab(vm: KatanaViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Channel / preset selection via Program Change
        SectionCard("Каналы / пресеты (Program Change)") {
            Text(
                "Кнопки шлют Program Change. Если Gen 3 использует другую нумерацию — " +
                    "поправишь в одном месте.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..8).forEach { n ->
                    OutlinedButton(onClick = { vm.selectProgram(n - 1) }) { Text("$n") }
                }
            }
        }

        KatanaParams.BY_CATEGORY.forEach { (category, params) ->
            SectionCard(category) {
                params.forEach { p -> ParamControl(vm, p) }
            }
        }

        Text(
            "⚠️ Адреса параметров (кроме Reverb type) — предварительные, из карты Gen2. " +
                "Проверяй на своём Gen 3 через вкладку «Консоль».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamControl(vm: KatanaViewModel, p: KatanaParam) {
    val value = vm.paramValues[p.id] ?: p.default
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(p.label + if (!p.verified) "  (?)" else "")
            if (p.kind == ParamKind.CONTINUOUS) Text("$value")
        }
        when (p.kind) {
            ParamKind.CONTINUOUS -> Slider(
                value = value.toFloat(),
                onValueChange = { vm.setParam(p, it.toInt()) },
                valueRange = p.min.toFloat()..p.max.toFloat(),
            )
            ParamKind.TOGGLE -> Switch(
                checked = value != 0,
                onCheckedChange = { vm.setParam(p, if (it) 1 else 0) },
            )
            ParamKind.ENUM -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.options.forEachIndexed { i, name ->
                    FilterChip(
                        selected = value == i,
                        onClick = { vm.setParam(p, i) },
                        label = { Text(name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleTab(vm: KatanaViewModel) {
    var hex by remember { mutableStateOf("F0 41 00 00 00 00 33 11 60 00 00 00 00 00 00 10 10 F7") }
    var result by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("60 00 00 00") }
    var size by remember { mutableStateOf("16") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Отправить сырой SysEx") {
            OutlinedTextField(
                value = hex,
                onValueChange = { hex = it },
                label = { Text("hex-байты") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { result = vm.sendRawHex(hex) }) { Text("Отправить") }
            if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Прочитать блок (для мэппинга Gen 3)") {
            Text(
                "Считай блок, покрути ручку на комбике физически, считай снова и сравни — " +
                    "так находятся адреса Gen 3.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = addr, onValueChange = { addr = it },
                label = { Text("адрес (4 байта hex)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = size, onValueChange = { size = it },
                label = { Text("сколько байт") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                result = vm.readBlockHex(addr, size.toIntOrNull() ?: 16)
            }) { Text("Запросить") }
        }

        SectionCard("Лог (TX/RX)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.clearLog() }) { Text("Очистить") }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                vm.log.takeLast(200).forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTab(vm: KatanaViewModel) {
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Сохранить текущий тон") {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("название пресета") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { if (name.isNotBlank()) { vm.capturePatch(name); name = "" } },
            ) { Text("Сохранить") }
        }

        SectionCard("Мои пресеты") {
            if (vm.patches.isEmpty()) {
                Text("Пока пусто.", style = MaterialTheme.typography.bodySmall)
            }
            vm.patches.forEach { patch ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(patch.name, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.applyPatch(patch) }) { Text("Загрузить") }
                    OutlinedButton(
                        onClick = { vm.deletePatch(patch.name) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("✕") }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
