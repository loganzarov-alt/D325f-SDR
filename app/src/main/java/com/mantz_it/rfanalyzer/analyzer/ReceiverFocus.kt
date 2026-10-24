// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.analyzer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Session-only state: freezing never persists across an application restart. */
object ReceiverFocus {
    @Volatile var audioActive = false
    private val frozenState = MutableStateFlow(false)
    val frozen = frozenState.asStateFlow()
    val paused: Boolean get() = frozenState.value
    fun resume() { frozenState.value = false }
    fun toggle() { frozenState.value = !frozenState.value }
}
