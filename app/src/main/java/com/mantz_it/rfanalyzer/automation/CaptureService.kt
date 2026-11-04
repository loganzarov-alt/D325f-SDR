// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.automation

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.source.*
import com.mantz_it.nativedsp.NativeDsp
import org.json.JSONObject
import java.io.*
import java.util.concurrent.*
import java.net.*
import kotlin.math.*

/** Owns the modem independently of Activities. No sticky restart or persisted thermal latch. */
@dagger.hilt.android.AndroidEntryPoint
class CaptureService : Service() {
    @javax.inject.Inject lateinit var recordingDao: com.mantz_it.rfanalyzer.database.RecordingDao
    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLanguage.wrap(base)) }
    private var recordingFile: File? = null
    private var recordingRaw: FileOutputStream? = null
    companion object { const val STOP = "capture.stop" }
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var cancelled = false
    @Volatile private var source: LgModemSource? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    override fun onBind(i: Intent?) = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if(com.mantz_it.rfanalyzer.BuildConfig.NETWORK_CLIENT) { stopSelf();return START_NOT_STICKY }
        if(i?.action==STOP) {
            cancelled=true; CaptureSettings.cancelRequested=true
            runCatching { client?.close() };runCatching { server?.close() }
            if(worker?.isAlive!=true)stopSelf()
            return START_NOT_STICKY
        }
        if(worker?.isAlive==true) {
            i?.getStringExtra("task")?.let { json ->
                if(!i.getBooleanExtra("stream",false)) TaskStore.mark(this,CaptureTask.from(JSONObject(json)).id,"skipped")
            }
            return START_NOT_STICKY
        }
        val json=i?.getStringExtra("task") ?: run { stopSelf(); return START_NOT_STICKY }
        val task=CaptureTask.from(JSONObject(json)); val streaming=i.getBooleanExtra("stream",false)
        val stop=PendingIntent.getService(this,0,Intent(this,CaptureService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        startForeground(2,NotificationCompat.Builder(this,"SERVICE_CHANNEL")
            .setSmallIcon(R.drawable.stop_circle).setContentTitle("D325f SDR 2.0")
            .setContentText(if(streaming) "IQ TCP" else getString(R.string.ui_6))
            .setOngoing(true).addAction(R.drawable.stop_circle,getString(R.string.v2_stop),stop).build())
        if(!CaptureSettings.acquire(this)) { CaptureSettings.status.value=getString(R.string.v2_busy); if(!streaming)TaskStore.mark(this,task.id,"skipped"); stopSelf(); return START_NOT_STICKY }
        cancelled=false; CaptureSettings.cancelRequested=false; CaptureSettings.busy.value=true
        wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"D325f:Capture").apply { acquire() }
        worker=Thread({
            try { if(streaming) stream(task) else automate(task) }
            catch(e: Exception) {
                if(!streaming)runCatching { TaskStore.mark(this,task.id,if(stopped())"cancelled" else "failed") }
                CaptureSettings.status.value="${getString(R.string.v2_error)}: ${UiTranslations.text(this,e.message ?: "Error")}"
            }
            finally {
                source?.close(); source=null
                runCatching { client?.close() }; runCatching { server?.close() }
                CaptureSettings.busy.value=false; CaptureSettings.release(this)
                wake?.let { if(it.isHeld)it.release() }; stopForeground(true); stopSelf()
            }
        },"Capture-2.0").apply { start() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        cancelled=true
        runCatching { client?.close() }; runCatching { server?.close() }
        super.onDestroy()
    }
    private fun stopped()=cancelled || CaptureSettings.cancelRequested
    private fun power(reboot: Boolean) {
        if(stopped())return
        val command=if(reboot) "reboot" else "reboot -p"
        val p=ProcessBuilder("su","-c",command).redirectErrorStream(true).start()
        val deadline=SystemClock.elapsedRealtime()+15000
        while(true) {
            val code=try { p.exitValue() } catch(_: IllegalThreadStateException) { null }
            if(code!=null) { check(code==0) { "Power command failed" };break }
            if(SystemClock.elapsedRealtime()>=deadline) { p.destroy();throw IOException(getString(R.string.l10n_power_timeout)) }
            Thread.sleep(100)
        }
    }
    private fun temperatures(): DoubleArray {
        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Int.MIN_VALUE)
            ?.takeIf { it!=Int.MIN_VALUE }?.div(10.0) ?: Double.NaN
        val cpus=File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.mapNotNull { z ->
            runCatching {
                val type=File(z,"type").readText().trim().lowercase()
                if(!type.contains("tsens") && !type.contains("cpu") && !type.contains("soc")) null
                else File(z,"temp").readText().trim().toDouble().let { if(abs(it)>500)it/1000 else it }.takeIf { it in -40.0..150.0 }
            }.getOrNull()
        } ?: emptyList()
        return doubleArrayOf(battery,cpus.maxOrNull() ?: Double.NaN)
    }
    private fun logTemperature(t: CaptureTask, values: DoubleArray) {
        File(filesDir,"automation-temperature.csv").apply {
            if(length()>2_000_000)writeText("")
            appendText("${System.currentTimeMillis()},${t.id},${values[0]},${values[1]}\n")
        }
    }
    private fun createSource(t: CaptureTask): LgModemSource {
        val ready=CountDownLatch(1); var error: String?=null
        val s=LgModemSource(t.frequency,t.band,0,240,t.profile,t.multiplier,t.lna,t.dvga)
        source=s; s.setInvertSpectrum(t.inverted); s.sampleRate=t.rate
        s.open(this,object: IQSourceInterface.Callback {
            override fun onIQSourceReady(source: IQSourceInterface) { ready.countDown() }
            override fun onIQSourceError(source: IQSourceInterface,message: String) { error=message; ready.countDown() }
        })
        if(!ready.await(20,TimeUnit.SECONDS) || !s.isOpen) {
            s.close(); throw IOException(error ?: getString(R.string.l10n_modem_timeout))
        }
        val bt=BtCwController.get(this)
        bt.stop(); bt.setChannel(if(t.frequency<100_000_000 && t.btChannel>30)0 else t.btChannel); bt.setPower(t.btPower)
        // Session uses its own BT boundary; restore the user's preference on completion.
        getSharedPreferences("bt_rx_limit",0).edit().putLong("upper_hz",t.btLimit).commit(); BtRxLimit.reload()
        return s
    }
    private class Encoder(val rate: Int): AutoCloseable {
        private val dsp=NativeDsp()
        private val handle=if(rate==LgModemSource.SAMPLE_RATE)0L else dsp.createBandwidth(rate)
        private val re=FloatArray(16384); private val im=FloatArray(16384)
        fun bytes(raw: ByteArray, unsigned: Boolean=false): ByteArray {
            val data=if(handle==0L)raw.copyOf() else {
                val n=dsp.filterBandwidth(handle,raw,re,im,false)
                ByteArray(n*2).apply { for(i in 0 until n) { this[i*2]=(re[i]*128).roundToInt().coerceIn(-128,127).toByte();this[i*2+1]=(im[i]*128).roundToInt().coerceIn(-128,127).toByte() } }
            }
            if(unsigned)for(i in data.indices)data[i]=(data[i].toInt() xor 128).toByte()
            return data
        }
        override fun close() { if(handle!=0L)dsp.destroyBandwidth(handle) }
    }
    private fun output(t: CaptureTask): BufferedOutputStream {
        val directory=if(t.folder.isBlank())com.mantz_it.rfanalyzer.ui.RecordingStorage.preferredDirectory(this) else File(t.folder)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording folder" }
        val name="${t.start}_${t.frequency}Hz_${t.rate}Sps_CI8_${t.id.take(8)}.iq"
        val f=File(directory,name); check(f.createNewFile()) { "Recording already exists" }
        recordingFile=f
        val raw=FileOutputStream(f);recordingRaw=raw
        return BufferedOutputStream(raw,1024*1024)
    }
    private data class Packet(val bytes: ByteArray,val at: Long)
    private fun automate(t: CaptureTask) {
        TaskStore.mark(this,t.id,"running")
        val initialBt=BtCwController.get(this)
        val initialChannel=BtCwController.getChannel();val initialPower=initialBt.power
        val initialBtLimit=getSharedPreferences("bt_rx_limit",0).getLong("upper_hz",BtRxLimit.DEFAULT_HZ)
        val start=SystemClock.elapsedRealtime(); val end=start+max(0,t.end-System.currentTimeMillis())
        val queue=ArrayBlockingQueue<Packet>(128)
        val policy=CapturePolicy(start)
        val varFlag=VolatileBox()
        var stream: BufferedOutputStream?=null
        var firstAt=0L; var written=0L; var failure=false; var storageError=false
        recordingFile=null;recordingRaw=null
        val zero=ByteArray(65536)
        var encoder=Encoder(t.rate)
        fun zeros(until: Long) {
            written=IqGap.fill(stream,written,firstAt,until,t.rate,zero) { stopped() }
        }
        val capture=Thread({
            var s: LgModemSource?=null
            try {
                while(!varFlag.done && !stopped() && SystemClock.elapsedRealtime()<end) {
                    try {
                        if(s==null)s=createSource(t)
                        s.setLosslessConsumerMode(true); s.startSampling()
                        while(!varFlag.done && !stopped() && SystemClock.elapsedRealtime()<end) {
                            val raw=s.getPacket(250)
                            if(raw==null) { varFlag.outage=true;break }
                            varFlag.outage=false
                            val now=SystemClock.elapsedRealtime()
                            val copy=raw.copyOf();s.returnPacket(raw)
                            while(!varFlag.done && !stopped() && !queue.offer(Packet(copy,now),200,TimeUnit.MILLISECONDS)) { }
                        }
                    } catch(e: Exception) { varFlag.outage=true;CaptureSettings.status.value="${getString(R.string.v2_recovering)}: ${UiTranslations.text(this,e.message ?: "Error")}" }
                    s?.stopSampling()
                    if(!varFlag.done && !stopped())Thread.sleep(500)
                }
            } finally { varFlag.closed=s?.close() ?: true }
        },"Automation-RX").apply { start() }
        try {
            var lastTemp=0L
            while(!stopped()) {
                val now=SystemClock.elapsedRealtime()
                if(now-lastTemp>=10_000) {
                    val temp=temperatures();logTemperature(t,temp);lastTemp=now
                    if(t.temperature && temp.all { it.isNaN() }) { varFlag.done=true;capture.join(30_000);stream?.flush();if(stream!=null)recordingRaw?.fd?.sync();stream?.close();stream=null;TaskStore.mark(this,t.id,"sensor-error");power(true);return }
                }
                val packet=queue.poll(100,TimeUnit.MILLISECONDS)
                if(packet!=null && packet.at<=end) {
                    policy.packet(packet.at) // Any real packet resets the failure timer.
                    if(stream==null) { stream=output(t);firstAt=packet.at }
                    if(varFlag.gap) {
                        zeros(min(end,packet.at-rawDurationMs(packet.bytes)))
                        encoder.close();encoder=Encoder(t.rate);varFlag.gap=false
                    }
                    val bytes=encoder.bytes(packet.bytes)
                    val maximum=max(0,end-firstAt)*t.rate/1000*2
                    val count=min(bytes.size.toLong(),max(0,maximum-written)).toInt()
                    stream!!.write(bytes,0,count);written+=count
                    CaptureSettings.status.value="${getString(R.string.v2_recording)} · ${written/1048576} MiB"
                } else if(now-policy.lastPacketMs()>=4500 && queue.isEmpty()) {
                    varFlag.gap=true;zeros(min(now,end))
                    CaptureSettings.status.value=getString(R.string.v2_recovering)
                }
                if(policy.failed(now)) { failure=true;break }
                if(now>=end) {
                    if(capture.isAlive && now-end<5000) continue
                    // If the task ended during an unresolved outage, preserve its five-minute shutdown deadline.
                    if(!policy.hasReceived() || varFlag.gap || varFlag.outage) {
                        varFlag.done=true
                        if(!capture.isAlive && queue.isEmpty()) { zeros(end);stream?.close();stream=null }
                        continue
                    }
                    break
                }
            }
        } catch(e: Exception) { storageError=true;CaptureSettings.status.value="${getString(R.string.v2_error)}: ${UiTranslations.text(this,e.message ?: "Error")}" }
        finally {
            varFlag.done=true
            capture.join(30_000)
            if((capture.isAlive || !varFlag.closed) && !stopped()) failure=true
            runCatching { if(stream!=null) { stream!!.flush();recordingRaw?.fd?.sync();stream!!.close() } }.onFailure { storageError=true }
            recordingFile?.let { file ->
                runCatching { kotlinx.coroutines.runBlocking {
                    recordingDao.insert(com.mantz_it.rfanalyzer.database.Recording(name="Automation",frequency=t.frequency,
                        sampleRate=t.rate.toLong(),date=t.start,fileFormat=com.mantz_it.rfanalyzer.ui.composable.FilesourceFileFormat.CI8,
                        sizeInBytes=file.length(),filePath=file.absolutePath,favorite=false))
                } }.onFailure { storageError=true }
            }
            encoder.close()
            getSharedPreferences("bt_rx_limit",0).edit().putLong("upper_hz",initialBtLimit).commit();BtRxLimit.reload()
            if(!capture.isAlive)runCatching { initialBt.setChannel(initialChannel);initialBt.setPower(initialPower) }
        }
        if(stopped()) { TaskStore.mark(this,t.id,"cancelled");return }
        if(failure) { TaskStore.mark(this,t.id,"failed");power(false);return }
        TaskStore.mark(this,t.id,if(storageError)"storage-error" else "completed")
        if(t.reboot) { power(true);return }
        if(t.temperature) {
            val baseline=temperatures();val coolingStart=SystemClock.elapsedRealtime()
            while(!stopped()) {
                val current=temperatures();logTemperature(t,current)
                CaptureSettings.status.value="${getString(R.string.v2_cooling)} · ${current.joinToString()} °C"
                when(CapturePolicy.cooling(baseline,current,SystemClock.elapsedRealtime()-coolingStart)) {
                    1 -> break
                    2 -> { power(false);return }
                    3 -> { power(true);return }
                }
                Thread.sleep(1000)
            }
        }
        CaptureSettings.status.value=getString(R.string.v2_completed)
    }
    private class VolatileBox { @Volatile var done=false; @Volatile var closed=true; @Volatile var outage=true; var gap=false }
    private fun rawDurationMs(raw: ByteArray)=raw.size.toLong()*1000/(2*LgModemSource.SAMPLE_RATE)

    /** Standard RTL0/CU8 server; commands arrive before potentially slow modem setup. */
    private fun stream(t: CaptureTask) {
        val socket=ServerSocket().apply { reuseAddress=true;bind(InetSocketAddress(CaptureSettings.prefs(this@CaptureService).getInt("port",1234)));soTimeout=1000 }
        server=socket
        val wifi=getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        val wifiLock=wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"D325f:IQ").apply { acquire() }
        val initialLimit=getSharedPreferences("bt_rx_limit",0).getLong("upper_hz",BtRxLimit.DEFAULT_HZ)
        var lastError=""
        fun diagnostic(message: String) {
            val f=File(filesDir,"network.log")
            runCatching { if(f.length()>256000)f.writeText("");f.appendText("${System.currentTimeMillis()} $message\n") }
        }
        try {
            while(!stopped()) {
                CaptureSettings.status.value="${getString(R.string.v2_listening)} :${socket.localPort} $lastError"
                val c=try { socket.accept() } catch(e: SocketTimeoutException) { continue }
                lastError="";client=c;c.tcpNoDelay=true;c.sendBufferSize=262144
                val controls=com.mantz_it.rfanalyzer.network.RtlTcpControl(t.frequency,t.rate)
                var reader: Thread?=null;var s: LgModemSource?=null
                val dsp=NativeDsp();var handle=0L;var rate=0
                val controlError=java.util.concurrent.atomic.AtomicReference<String?>(null)
                try {
                    val out=BufferedOutputStream(c.getOutputStream(),65536)
                    com.mantz_it.rfanalyzer.network.RtlTcpControl.greeting(out)
                    reader=Thread({
                        try { controls.read(c.getInputStream()) }
                        catch(e: Exception) { controlError.set(e.message);runCatching { c.close() } }
                    },"rtl_tcp-control").apply { start() }
                    // Clients send their initial tuning/rate immediately after RTL0.
                    val until=SystemClock.elapsedRealtime()+1000
                    var previous=-1L;var quiet=SystemClock.elapsedRealtime()
                    while(!stopped() && !c.isClosed && SystemClock.elapsedRealtime()<until) {
                        val now=SystemClock.elapsedRealtime();val rev=controls.snapshot().revision
                        if(rev!=previous){previous=rev;quiet=now}
                        if(rev>=2 && now-quiet>=150)break
                        Thread.sleep(20)
                    }
                    if(stopped() || c.isClosed)continue
                    val first=controls.snapshot()
                    CaptureSettings.status.value="RTL-TCP · ${c.inetAddress.hostAddress} · FTM"
                    s=createSource(t.copy(frequency=first.correctedFrequency().coerceIn(LgModemSource.MIN_FREQUENCY,LgModemSource.MAX_FREQUENCY)))
                    s.setLosslessConsumerMode(true);s.startSampling()
                    val output=ByteArray(32768)
                    var appliedFrequency=first.correctedFrequency();var lastPacket=SystemClock.elapsedRealtime()
                    var lastDiagnostic=""
                    while(!stopped() && !c.isClosed) {
                        val state=controls.snapshot()
                        if(rate!=state.rate) { if(handle!=0L)dsp.destroyNetworkResampler(handle);handle=0;handle=dsp.createNetworkResampler(state.rate);rate=state.rate }
                        val frequency=state.correctedFrequency().coerceIn(LgModemSource.MIN_FREQUENCY,LgModemSource.MAX_FREQUENCY)
                        if(frequency!=appliedFrequency){s.frequency=frequency;appliedFrequency=frequency}
                        if(state.diagnostic!=lastDiagnostic){lastDiagnostic=state.diagnostic;diagnostic(lastDiagnostic)}
                        val raw=s.getPacket(1000)
                        if(raw==null) {
                            if(SystemClock.elapsedRealtime()-lastPacket>60000)throw IOException("No modem IQ for 60 seconds")
                            CaptureSettings.status.value=getString(R.string.v2_recovering)
                            s.stopSampling();if(!stopped()&&!c.isClosed){Thread.sleep(500);s.startSampling()}
                            continue
                        }
                        try { val n=dsp.resampleNetwork(handle,raw,output,state.linearGain(),state.agc);out.write(output,0,n);out.flush() }
                        finally { s.returnPacket(raw) }
                        lastPacket=SystemClock.elapsedRealtime()
                        CaptureSettings.status.value="RTL-TCP · ${c.inetAddress.hostAddress} · $rate Sps · software gain ${state.gain/10.0} dB"
                    }
                    controlError.get()?.let { lastError=it }
                } catch(e: Exception) { if(!stopped()){lastError=controlError.get() ?: e.message ?: "Network error";diagnostic(lastError)} }
                finally {
                    if(handle!=0L)dsp.destroyNetworkResampler(handle)
                    runCatching { c.close() };s?.close();source?.close();source=null;reader?.join(1000)
                }
            }
        } finally {
            socket.close();if(wifiLock.isHeld)wifiLock.release()
            getSharedPreferences("bt_rx_limit",0).edit().putLong("upper_hz",initialLimit).commit();BtRxLimit.reload()
        }
    }
}
