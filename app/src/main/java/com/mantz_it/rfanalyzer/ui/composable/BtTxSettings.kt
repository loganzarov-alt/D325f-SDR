// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.mantz_it.rfanalyzer.source.BtCwController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BtTxSettings(frequency: Long = Long.MAX_VALUE) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val captureBusy by com.mantz_it.rfanalyzer.automation.CaptureSettings.busy.collectAsState()
    val maximumChannel = if (frequency < 100_000_000L) 30 else 78
    var channelText by remember { mutableStateOf("30") }
    var power by remember { mutableIntStateOf(7) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { BtCwController.get(context).power } }.onSuccess { power = it; channelText = BtCwController.getChannel().toString() }
    }
    OutlinedBox(label = "BT TX") {
        Column(Modifier.padding(12.dp)) {
            if(power >= 8) Text(androidx.compose.ui.res.stringResource(com.mantz_it.rfanalyzer.R.string.v2_bt_warning), color=MaterialTheme.colorScheme.error)
            OutlinedTextField(
                value = channelText,
                onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) channelText = it },
                label = { Text("BT (0–$maximumChannel)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy && !captureBusy
            )
            val selectedChannel = channelText.toIntOrNull()
            Text(if (selectedChannel != null && selectedChannel in 0..maximumChannel)
                "Частота TX: ${2402 + selectedChannel} МГц" else "Введите канал 0–78")
            Text("Смена канала — при остановленном приёме.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && !captureBusy && selectedChannel != null && selectedChannel in 0..maximumChannel, onClick = {
                val next = selectedChannel ?: return@Button
                busy = true
                scope.launch {
                    status = try {
                        withContext(Dispatchers.IO) { BtCwController.get(context).setChannel(next) }
                        "Сохранён канал $next · ${2402 + next} МГц"
                    } catch (e: Exception) { e.message ?: "Ошибка изменения канала" }
                    busy = false
                }
            }) { Text("Применить канал") }
            Text("Мощность TX: уровень $power / 9")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = power > 0 && !busy && !captureBusy, onClick = {
                    val next = power - 1; busy = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { BtCwController.get(context).setPower(next) }; power = next }
                        catch (e: Exception) { status = "TX: ${e.message}" }
                        busy = false
                    }
                }) { Text("TX −") }
                Button(enabled = power < 9 && !busy && !captureBusy, onClick = {
                    val next = power + 1; busy = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { BtCwController.get(context).setPower(next) }; power = next }
                        catch (e: Exception) { status = "TX: ${e.message}" }
                        busy = false
                    }
                }) { Text("TX +") }
            }
            Button(onClick = { com.mantz_it.rfanalyzer.source.BtRxLimit.show(context) }) { Text("Верхняя граница BT RX, МГц") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
