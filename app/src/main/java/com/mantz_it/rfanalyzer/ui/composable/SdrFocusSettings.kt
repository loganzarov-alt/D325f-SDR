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
import com.mantz_it.rfanalyzer.source.SdrFocusController

@Composable
fun SdrFocusSettings() {
    val context = LocalContext.current
    val controller = remember { SdrFocusController.get(context) }
    var mode by remember { mutableStateOf(controller.mode) }
    OutlinedBox(label = "Производительность") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Выключено", "Очистка RAM + CPU", "Заморозка фона и System UI + CPU").forEachIndexed { index, label ->
                TextButton(onClick = { mode = index; controller.setMode(index) }) {
                    RadioButton(selected = mode == index, onClick = null)
                    Text(label)
                }
            }
            Text(when (mode) {
                2 -> "Приостанавливает фоновые приложения, лаунчер, Google-службы и шторку. Службы экрана, звука и приёма продолжают работать. Заморозка не освобождает RAM. Этот режим может вернуть чёрные экраны."
                1 -> "Освобождает RAM фоновых приложений. Шторка работает; Android может запускать службы снова."
                else -> "Без очистки, заморозки и изменения режима CPU."
            }, style = MaterialTheme.typography.bodySmall)
            Text("Выбранный режим применяется при следующем запуске приёма. Выключение действует сразу. Стоп, уход из приложения или потеря связи с ним снимают заморозку и возвращают режим CPU.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
