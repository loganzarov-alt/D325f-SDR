// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.network
import kotlinx.coroutines.flow.MutableStateFlow
object NetworkClientStatus {
    val text = MutableStateFlow("")
    private var lastUpdate = 0L
    private var previousStage = ""
    @JvmStatic @Synchronized fun update(stage: String, bytes: Long) {
        val now = System.nanoTime()
        if (stage != previousStage || now-lastUpdate > 250_000_000L) {
            text.value = stage + if(bytes>0) " · $bytes bytes" else ""
            previousStage=stage;lastUpdate=now
        }
    }
}
