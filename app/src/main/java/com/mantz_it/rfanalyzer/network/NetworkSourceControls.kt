// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.network
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mantz_it.rfanalyzer.R
@Composable fun NetworkSourceControls(enabled: Boolean) {
 val context=LocalContext.current
 val prefs=remember { context.getSharedPreferences("network_client",0) }
 var host by remember { mutableStateOf(prefs.getString("host","192.168.1.69") ?: "") }
 var port by remember { mutableStateOf(prefs.getInt("port",1234).toString()) }
 var gain by remember { mutableStateOf((prefs.getInt("gain",0)/10.0).toString()) }
 var agc by remember { mutableStateOf(prefs.getBoolean("agc",false)) }
 OutlinedTextField(value=host,onValueChange={host=it;prefs.edit().putString("host",it.trim()).apply()},label={Text(stringResource(R.string.net_host))},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())
 OutlinedTextField(value=port,onValueChange={port=it;it.toIntOrNull()?.takeIf { n->n in 1..65535 }?.let { n->prefs.edit().putInt("port",n).apply() }},label={Text(stringResource(R.string.net_port))},isError=port.toIntOrNull() !in 1..65535,singleLine=true,enabled=enabled)
 OutlinedTextField(value=gain,onValueChange={gain=it;it.toDoubleOrNull()?.takeIf { n->n in -60.0..60.0 }?.let { n->prefs.edit().putInt("gain",(n*10).toInt()).apply() }},label={Text(stringResource(R.string.net_gain))},singleLine=true,enabled=enabled&&!agc)
 Row { Checkbox(checked=agc,onCheckedChange={agc=it;prefs.edit().putBoolean("agc",it).apply()},enabled=enabled);Text(stringResource(R.string.net_agc)) }
 Text(stringResource(R.string.net_help))
}
