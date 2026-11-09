// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.automation

import android.app.*
import android.content.*
import android.os.Build
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.source.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

object CaptureSettings {
    fun prefs(c: Context) = c.getSharedPreferences("release_2", Context.MODE_PRIVATE)
    fun inverted(c: Context) = prefs(c).getBoolean("invert", true)
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    @Volatile var cancelRequested = false
    // Only one capture owns the modem at a time: the live receiver or a scheduled recording.
    private var owner: Any? = null
    @Synchronized fun acquire(token: Any): Boolean {
        if (owner != null && owner !== token) return false
        owner = token; return true
    }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
}

data class CaptureTask(
    val id: String = UUID.randomUUID().toString(), val start: Long, val duration: Long,
    val frequency: Long, val rate: Int, val band: Int, val profile: Int,
    val multiplier: Int, val lna: Int, val dvga: Int, val inverted: Boolean,
    val btChannel: Int, val btPower: Int, val btLimit: Long,
    val folder: String, val reboot: Boolean, val temperature: Boolean,
    val state: String = "pending"
) {
    val end get() = start + duration
    fun json() = JSONObject().apply {
        put("id", id); put("start", start); put("duration", duration)
        put("frequency", frequency); put("rate", rate); put("band", band)
        put("profile", profile); put("multiplier", multiplier); put("lna", lna); put("dvga", dvga)
        put("inverted", inverted); put("btChannel", btChannel); put("btPower", btPower); put("btLimit", btLimit)
        put("folder", folder); put("reboot", reboot); put("temperature", temperature); put("state", state)
    }
    companion object {
        fun from(j: JSONObject) = CaptureTask(j.getString("id"), j.getLong("start"), j.getLong("duration"),
            j.getLong("frequency"), j.getInt("rate"), j.getInt("band"), j.getInt("profile"),
            j.getInt("multiplier"), j.getInt("lna"), j.getInt("dvga"), j.getBoolean("inverted"),
            j.getInt("btChannel"), j.getInt("btPower"), j.getLong("btLimit"),
            j.getString("folder"), j.getBoolean("reboot"), j.getBoolean("temperature"), j.getString("state"))
        fun snapshot(c: Context, a: AppStateRepository, start: Long, duration: Long, frequency: Long,
                     reboot: Boolean, temperature: Boolean): CaptureTask {
            val bt = BtCwController.get(c)
            return CaptureTask(start=start, duration=duration, frequency=frequency,
                rate=a.sourceSampleRates.getValue(com.mantz_it.rfanalyzer.ui.composable.SourceType.LGMODEM).value.toInt(), band=a.lgRfBand.value, profile=a.lgPllProfile.value,
                multiplier=a.lgPllMultiplier.value, lna=a.lgLnaState.value, dvga=a.lgDvgaOffset.value,
                inverted=CaptureSettings.inverted(c), btChannel=BtCwController.getChannel(), btPower=bt.power,
                btLimit=BtRxLimit.activeHz, folder=CaptureSettings.prefs(c).getString("folder", "") ?: "",
                reboot=reboot, temperature=temperature)
        }
    }
}

object TaskStore {
    @Synchronized fun all(c: Context): List<CaptureTask> {
        val a=JSONArray(CaptureSettings.prefs(c).getString("tasks", "[]"))
        return (0 until a.length()).map { CaptureTask.from(a.getJSONObject(it)) }
    }
    @Synchronized private fun write(c: Context, list: List<CaptureTask>) {
        check(CaptureSettings.prefs(c).edit().putString("tasks", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).commit())
    }
    @Synchronized fun add(c: Context, t: CaptureTask) {
        require(t.start > System.currentTimeMillis() && t.duration > 0 && t.end > t.start)
        require(t.frequency in LgModemSource.MIN_FREQUENCY..LgModemSource.MAX_FREQUENCY)
        val list=all(c)
        require(list.none { it.state in listOf("pending","running") && CapturePolicy.overlaps(t.start,t.end,it.start,it.end) }) { "Tasks overlap" }
        schedule(c,t) // Fail before saving if exact alarms are unavailable.
        write(c,list+t)
    }
    @Synchronized fun mark(c: Context,id: String,state: String) = write(c,all(c).map { if(it.id==id) it.copy(state=state) else it })
    @Synchronized fun delete(c: Context,id: String) {
        require(all(c).none { it.id==id && it.state=="running" })
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c,id))
        write(c,all(c).filterNot { it.id==id })
    }
    private fun pending(c: Context,id: String) = PendingIntent.getBroadcast(c,0,
        Intent(c,TaskAlarm::class.java).setAction(id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(c: Context,t: CaptureTask) {
        val am=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if(Build.VERSION.SDK_INT>=31) check(am.canScheduleExactAlarms()) { "Allow exact alarms in system settings" }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,t.start,pending(c,t.id))
    }
    @Synchronized fun afterBoot(c: Context) {
        val now=System.currentTimeMillis()
        val list=all(c).map { if(it.state=="running" || (it.state=="pending" && it.start<=now)) it.copy(state="skipped") else it }
        write(c,list)
        list.filter { it.state=="pending" }.forEach { schedule(c,it) }
    }
}
class TaskAlarm: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        if(i.action==Intent.ACTION_BOOT_COMPLETED) { TaskStore.afterBoot(c); return }
        val task=TaskStore.all(c).firstOrNull { it.id==i.action && it.state=="pending" } ?: return
        if(System.currentTimeMillis()>=task.end) { TaskStore.mark(c,task.id,"skipped"); return }
        val start=Intent(c,CaptureService::class.java).putExtra("task",task.json().toString())
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(start) else c.startService(start)
    }
}
