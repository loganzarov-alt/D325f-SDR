// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.automation

import android.content.*
import android.content.res.Configuration
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.ui.composable.ScrollableColumnWithFadingEdge
import com.mantz_it.rfanalyzer.ui.RecordingStorage
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

object AppLanguage {
    val languages=linkedMapOf("en" to "English","ru" to "Русский","es" to "Español","pt" to "Português",
        "fr" to "Français","de" to "Deutsch","it" to "Italiano","zh-Hans" to "简体中文","zh-Hant" to "繁體中文",
        "ja" to "日本語","ko" to "한국어","ar" to "العربية","hi" to "हिन्दी")
    fun wrap(c: Context): Context {
        val p=CaptureSettings.prefs(c)
        if(!p.getBoolean("manual_language",false)) return c
        val locale=Locale.forLanguageTag(p.getString("language","en") ?: "en")
        val config=Configuration(c.resources.configuration);config.setLocale(locale);config.setLayoutDirection(locale)
        return c.createConfigurationContext(config)
    }
}
@Composable fun LanguageSettings() {
    val c=LocalContext.current;val p=CaptureSettings.prefs(c)
    var manual by remember { mutableStateOf(p.getBoolean("manual_language",false)) }
    var language by remember { mutableStateOf(p.getString("language","en") ?: "en") }
    var menu by remember { mutableStateOf(false) }
    fun save() {
        p.edit().putBoolean("manual_language",manual).putString("language",language).commit()
        (c as? Activity)?.recreate()
    }
    Row { Checkbox(manual,{manual=it;save()});Text(stringResource(R.string.v2_manual_language)) }
    if(manual) {
        Box {
            OutlinedButton({menu=true}) { Text(AppLanguage.languages[language] ?: "English") }
            DropdownMenu(menu,{menu=false}) { AppLanguage.languages.forEach { (tag,name) ->
                DropdownMenuItem(text={Text(name)},onClick={language=tag;menu=false;save()})
            } }
        }
    } else Text(stringResource(R.string.v2_system_language))
}

@Composable fun BackgroundAccess() {
    val c=LocalContext.current
    var allowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while(true) {
            allowed=(c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(c.packageName)
            delay(1000)
        }
    }
    if(!allowed) OutlinedButton(onClick={
        runCatching {
            c.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${c.packageName}")))
        }.onFailure { c.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }) { Text(stringResource(R.string.v2_background)) }
}

@Composable fun CaptureSourceControls(a: AppStateRepository) {
    val c=LocalContext.current;val p=CaptureSettings.prefs(c);val scope=rememberCoroutineScope()
    val running by a.analyzerRunning.stateFlow.collectAsState();val pending by a.analyzerStartPending.stateFlow.collectAsState()
    val busy by CaptureSettings.busy.collectAsState();val status by CaptureSettings.status.collectAsState()
    var invert by remember { mutableStateOf(CaptureSettings.inverted(c)) }
    var streaming by remember { mutableStateOf(p.getBoolean("stream",false)) }
    var port by remember { mutableStateOf(p.getInt("port",1234).toString()) }
    var error by remember { mutableStateOf("") }
    val recording by a.recordingRunning.stateFlow.collectAsState()
    val locked=running || pending || busy || recording
    Column {
        Row { Checkbox(invert,{invert=it;p.edit().putBoolean("invert",it).commit()},enabled=!locked);Text(stringResource(R.string.v2_invert)) }
        Row { Checkbox(streaming,{streaming=it;p.edit().putBoolean("stream",it).commit()},enabled=!locked);Text(stringResource(R.string.v2_stream)) }
        if(streaming) {
            BackgroundAccess()
            Text(stringResource(R.string.v2_stream_info))
            OutlinedTextField(port,{if(it.length<=5 && it.all(Char::isDigit))port=it},label={Text(stringResource(R.string.v2_port))},enabled=!locked)
            val addresses=remember { runCatching { Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }.filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.joinToString { it.hostAddress ?: "" } }.getOrDefault("") }
            Text("IP: $addresses")
            Button(onClick={
                if(busy) { CaptureSettings.cancelRequested=true;c.startService(Intent(c,CaptureService::class.java).setAction(CaptureService.STOP)) }
                else scope.launch {
                    runCatching {
                        val number=port.toInt();require(number in 1024..65535)
                        p.edit().putInt("port",number).commit()
                        val task=withContext(Dispatchers.IO) { CaptureTask.snapshot(c,a,System.currentTimeMillis(),86_400_000,a.sourceFrequency.value,false,false) }
                        val intent=Intent(c,CaptureService::class.java).putExtra("task",task.json().toString()).putExtra("stream",true)
                        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(intent) else c.startService(intent)
                    }.onFailure { error=it.message ?: "Error" }
                }
            },enabled=!running && !pending) { Text(stringResource(if(busy)R.string.v2_stop else R.string.v2_start_stream)) }
        }
        if(status.isNotEmpty())Text(status)
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    }
}

@Composable fun AutomationTab(a: AppStateRepository) {
    val c=LocalContext.current;val scope=rememberCoroutineScope();val p=CaptureSettings.prefs(c)
    val busy by CaptureSettings.busy.collectAsState();val status by CaptureSettings.status.collectAsState()
    var tasks by remember { mutableStateOf(TaskStore.all(c)) }
    val format=remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).apply { isLenient=false } }
    var start by remember { mutableStateOf(format.format(Date(System.currentTimeMillis()+300_000))) }
    var duration by remember { mutableStateOf("60") }
    var frequency by remember { mutableStateOf((a.sourceFrequency.value/1e6).toString()) }
    var reboot by remember { mutableStateOf(false) };var thermal by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(p.getString("folder","") ?: "") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while(true) { tasks=TaskStore.all(c);delay(1000) } }
    ScrollableColumnWithFadingEdge {
        BackgroundAccess()
        Text(stringResource(R.string.v2_task_settings))
        OutlinedTextField(start,{start=it},label={Text(stringResource(R.string.v2_start_time))})
        Text(TimeZone.getDefault().id)
        OutlinedTextField(duration,{duration=it},label={Text(stringResource(R.string.v2_duration))})
        OutlinedTextField(frequency,{frequency=it},label={Text(stringResource(R.string.v2_frequency))})
        Row { Checkbox(reboot,{reboot=it});Text(stringResource(R.string.v2_reboot)) }
        Row { Checkbox(thermal,{thermal=it});Text(stringResource(R.string.v2_thermal)) }
        Text(stringResource(R.string.v2_thermal_info))
        OutlinedTextField(folder,{folder=it},label={Text(stringResource(R.string.v2_folder))},modifier=Modifier.fillMaxWidth())
        RecordingStorage.candidateDirectories(c).forEach { dir ->
            TextButton({folder=dir.absolutePath}) { Text(dir.absolutePath) }
        }
        Button(onClick={scope.launch {
            runCatching {
                val whenMs=format.parse(start)?.time ?: throw IllegalArgumentException("Date")
                val seconds=duration.toLong();require(seconds in 1..604800)
                val mhz=frequency.replace(',','.').toDouble();require(mhz.isFinite())
                p.edit().putString("folder",folder).commit()
                val task=withContext(Dispatchers.IO) { CaptureTask.snapshot(c,a,whenMs,seconds*1000,(mhz*1e6).toLong(),reboot,thermal) }
                TaskStore.add(c,task);tasks=TaskStore.all(c);error=""
            }.onFailure { error=it.message ?: "Error" }
        }}) { Text(stringResource(R.string.v2_add)) }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(status.isNotBlank())Text(status)
        if(busy)Button({CaptureSettings.cancelRequested=true;c.startService(Intent(c,CaptureService::class.java).setAction(CaptureService.STOP))}) { Text(stringResource(R.string.v2_cancel)) }
        tasks.sortedBy { it.start }.forEach { task ->
            HorizontalDivider()
            Text("${format.format(Date(task.start))} · ${task.frequency/1e6} MHz · ${task.duration/1000} s")
            Text("${task.rate} S/s · BT ${task.btChannel}/${task.btPower} · ${task.state}")
            if(task.state!="running")TextButton({TaskStore.delete(c,task.id);tasks=TaskStore.all(c)}) { Text(stringResource(R.string.v2_delete)) }
        }
    }
}
