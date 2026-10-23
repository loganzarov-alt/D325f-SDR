// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.analyzer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mantz_it.rfanalyzer.source.FileIQSource
import com.mantz_it.rfanalyzer.source.IQSourceInterface
import com.mantz_it.rfanalyzer.source.LgModemSource
import com.mantz_it.rfanalyzer.LogcatLogger
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.ui.RecordingStorage
import com.mantz_it.rfanalyzer.ui.composable.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.FilesourceFileFormat
import com.mantz_it.rfanalyzer.ui.composable.SourceType
import com.mantz_it.rfanalyzer.ui.composable.StopAfterUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import java.io.File
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * <h1>RF Analyzer - Analyzer Service</h1>
 *
 * Module:      AnalyzerService.kt
 * Description: Foreground Service which orchestrates the signal processing pipeline.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

@AndroidEntryPoint
class AnalyzerService : Service() {
    @Inject lateinit var appStateRepository: AppStateRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job()) // Coroutine scope for observing the App State
    private val binder = LocalBinder()
    private var isBound = false

    var source: IQSourceInterface? = null
        private set
    var scheduler: Scheduler? = null
        private set
    var demodulator: Demodulator? = null
        private set
    var fftProcessor: FftProcessor? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): AnalyzerService = this@AnalyzerService
    }

    companion object {
        private const val TAG = "AnalyzerService"
        const val ACTION_STOP = "com.mantz_it.rfanalyzer.analyzer.ACTION_STOP"
    }

    private val iqSourceActions: IQSourceInterface.Callback = object : IQSourceInterface.Callback {
        override fun onIQSourceReady(source: IQSourceInterface?) {
            serviceScope.launch {
                if (startRequested && source === this@AnalyzerService.source) startScheduler()
            }
        }

        override fun onIQSourceError(source: IQSourceInterface?, message: String?) {
            val errorMessage = "Error with Source (${source?.getName()}): $message"
            Log.e(TAG, "onIQSourceError: $errorMessage")
            serviceScope.launch {
                if (source !== this@AnalyzerService.source) return@launch
                // Retry limits count consecutive failures, not a whole day of successful RX.
                if (source is LgModemSource && source.receivedBlockCount >= 30_000L)
                    recoveryAttempts = 0
                // A transient loss of an established stream must not exhaust the
                // lifetime retry budget. Configuration/startup failures remain bounded.
                val streamInterrupted = message?.contains("stream") == true
                if (source is LgModemSource && startRequested && !recoveryPending &&
                    (streamInterrupted || recoveryAttempts < 3) &&
                    (message?.contains("stream") == true || message?.contains("retune failed") == true ||
                     message?.startsWith("cannot start LG capture:") == true)) {
                    recoveryPending = true
                    recoveryAttempts = minOf(recoveryAttempts + 1, 10)
                    stopAnalyzer(releaseSource = false)
                    val epoch = recoveryEpoch
                    kotlinx.coroutines.delay(1000L * recoveryAttempts)
                    recoveryPending = false
                    if (epoch == recoveryEpoch && source === this@AnalyzerService.source) {
                        Log.w(TAG, "Recovering LG RX attempt $recoveryAttempts")
                        recoveryStarting = true
                        try { startAnalyzer() } finally { recoveryStarting = false }
                    } else if (!appStateRepository.analyzerRunning.value) stopForegroundService()
                    return@launch
                }
                if (recoveryPending) return@launch
                appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.SourceFailure("Source: $message"))
                stopAnalyzer(releaseSource = true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: Service is bound.")
        isBound = true
        return binder
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind: Service is bound.")
        isBound = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        if(appStateRepository.analyzerRunning.value) {
            Log.d(TAG, "onUnbind: Service is unbound. Keep running in the background")
            return true // don't terminate the service if the analyzer is still running
        } else {
            Log.d(TAG, "onUnbind: Service is unbound. Stop Service.")
            stopSelf()  // stop service if activity disconnects
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service created.")

        // Start Logging to file:
        if(appStateRepository.loggingEnabled.value)
            LogcatLogger.startLogging(this)

        handleAppStateChanges()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        when (intent?.action) {
            ACTION_STOP -> {
                // If the stop action is received from the notification's stop button
                Log.d(TAG, "onStartCommand: Stop action received from Notification. Stopping service.")
                stopAnalyzer()
                if(!isBound)
                    stopSelf() // Stop the service if activity is not connected
                return START_NOT_STICKY // Ensure the service is not restarted automatically
            }
            else -> {
                // This handles the case where the service is started by the activity or any other intent
                Log.d(TAG, "onStartCommand: Service started sticky.")
                // keep the service running:
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // Clean up the coroutine scope
        stopAnalyzer(releaseSource = true) // release held FTM on full service closure
        Log.d(TAG, "onDestroy: Service destroyed.")
        LogcatLogger.stopLogging()
    }

    private fun stopForegroundService() {
        Log.d(TAG, "stopForegroundService: removing notification.")
        stopForeground(STOP_FOREGROUND_REMOVE) // This removes the notification
        stopSelf() // remove the 'started' state so the service stops when unbound
    }

    private fun startForegroundService() {
        val notification = createNotification()
        Log.d(TAG, "startForegroundService: Moving service to foreground.")
        ServiceCompat.startForeground(this, 1, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // **Explicitly start the service so it doesn't stop when unbound**
        val serviceIntent = Intent(this, this::class.java)
        startService(serviceIntent)
    }

    private fun createNotification(): Notification {
        Log.d(TAG, "createNotification: Creating foreground notification.")

        // Intent to launch the main activity
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop the service
        val stopIntent = Intent(this, AnalyzerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "SERVICE_CHANNEL")
            .setContentTitle("D325f SDR")
            .setContentText("D325f SDR is receiving")
            .setSmallIcon(R.drawable.signal_wave)
            .setContentIntent(activityPendingIntent)
            .addAction(R.drawable.stop_circle, "Stop", pendingStopIntent)
            .build()
    }

    /**
     * Will stop the RF Analyzer. This includes shutting down the scheduler (which turns of the
     * source) and the demodulator if running.
     */
    private var recoveryStarting = false
    private var recoveryPending = false
    private var recoveryAttempts = 0
    private var recoveryEpoch = 0L
    @Volatile private var startRequested = false

    fun stopAnalyzer(releaseSource: Boolean = false) {
        com.mantz_it.rfanalyzer.source.SdrFocusController.get(this).stop()
        startRequested = false
        recoveryEpoch++
        Log.i(TAG, "stopAnalyzer")
        // Stop the Scheduler if running:
        scheduler?.stopScheduler()

        // Stop the Demodulator if running:
        demodulator?.stopDemodulator()

        fftProcessor?.stopLoop()

        // Wait for the scheduler to stop:
        try {
            if (scheduler?.name != Thread.currentThread().name)
                scheduler?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopAnalyzer: Error while stopping Scheduler.")
        }

        // All audio children receive stop before a replacement pipeline starts.
        try { demodulator?.join(3500) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        val keepFactory = !releaseSource && (source is LgModemSource)
        if (!keepFactory) {
            source?.close()
            source = null
        }

        appStateRepository.sourceName.set("")
        appStateRepository.analyzerRunning.set(false)
        appStateRepository.analyzerStartPending.set(false)
        GlobalPerformanceData.reset()

        if (!recoveryPending) stopForegroundService()
    }

    /**
     * Will start the RF Analyzer. This includes creating a source (if null), open a source
     * (if not open), starting the scheduler (which starts the source) and starting the
     * processing loop.
     */
    fun startAnalyzer() : Boolean {
        recoveryEpoch++
        if (!recoveryStarting) recoveryAttempts = 0
        if (appStateRepository.analyzerRunning.value) return true

        ReceiverFocus.resume()
        if (source != null && sourceKind != appStateRepository.sourceType.value) {
            stopAnalyzer(releaseSource = true)
        }
        startRequested = true
        Log.d(TAG, "startAnalyzer")
        if (source == null) {
            if (!this.createSource()) return false
        }

        // check if the source is open. if not, open it!
        if (!source!!.isOpen()) {
            if (!openSource()) {
                Toast.makeText(this, "Source not available (${source?.getName()})", Toast.LENGTH_LONG).show()
                source = null
                appStateRepository.analyzerStartPending.set(false)
                return false
            }
            return true // we have to wait for the source to become ready... onIQSourceReady() will call startScheduler()...
        } else {
            return startScheduler()
        }
    }

    private var sourceKind: SourceType? = null

    private fun effectiveFftSize(size: Int) = size

    private fun startScheduler(): Boolean {
        if (!startRequested) return false
        if(source == null) return false

        // Source was successfully opened, let the UI know:
        appStateRepository.sourceName.set(source!!.name)
        appStateRepository.sourceMinimumFrequency.set(source!!.minFrequency)
        appStateRepository.sourceMaximumFrequency.set(source!!.maxFrequency)
        appStateRepository.sourceMinimumSampleRate.set(source!!.minSampleRate.toLong())
        appStateRepository.sourceMaximumSampleRate.set(source!!.maxSampleRate.toLong())
        appStateRepository.sourceOptimalSampleRates.set(
            if (source is LgModemSource) source!!.supportedSampleRates.map { it.toLong() }
            else source!!.supportedSampleRates.map { it.toLong() }
        )
        appStateRepository.sourceFrequency.set(source!!.frequency)
        appStateRepository.sourceSampleRate.set(source!!.sampleRate.toLong())

        // Start fixed-bandwidth sources fitted to their complete RF span.  This is
        // especially important for wide 7.68 MSps IQ files: the persisted/default
        // 2 MHz viewport otherwise makes a 131072-point FFT look four-times zoomed
        // even though the FFT itself is correct.  Users can still pinch-zoom after
        // playback has started.  No IQ/DSP/demodulation data is changed here.
        if (source is LgModemSource || source is FileIQSource) {
            appStateRepository.viewportFrequency.set(source!!.frequency)
            appStateRepository.viewportSampleRate.set(source!!.sampleRate.toLong())
        }
        

        if (source is LgModemSource) {
            // One-time migration: discard pathological saved zoom/FFT settings.
            if (!appStateRepository.lgSmoothDisplayMigrated.value) {
                appStateRepository.fftSize.set(8192)
                appStateRepository.fftAverageLength.set(2)
                appStateRepository.maxFrameRate.set(20)
                appStateRepository.viewportVerticalScaleMin.set(-90f)
                appStateRepository.viewportVerticalScaleMax.set(0f)
                appStateRepository.lgSmoothDisplayMigrated.set(true)
            }
        }

        // Create a new instance of Scheduler
        scheduler = Scheduler(effectiveFftSize(appStateRepository.fftSize.value), source!!)

        // Start the demodulator thread:

        val demodSource = source!!
        val demodScheduler = scheduler
        demodulator = Demodulator(
            scheduler!!.demodOutputQueue,
            scheduler!!.demodInputQueue,
            scheduler!!.demodPacketSize,
            source!!.sampleRate,
            onFailure = { reason -> serviceScope.launch {
                if (scheduler === demodScheduler)
                    iqSourceActions.onIQSourceError(demodSource, "Audio stream failed: $reason")
            } }
        )
        demodulator!!.audioVolumeLevel = appStateRepository.effectiveAudioVolumeLevel.value
        demodulator!!.start()

        applyNewDemodulationMode(appStateRepository.demodulationMode.value)

        

        // Start the scheduler
        scheduler!!.start()

        fftProcessor = FftProcessor(
            initialFftSize = effectiveFftSize(appStateRepository.fftSize.value),
            inputQueue = scheduler!!.fftOutputQueue,  // Reference to the input queue for the processing loop
            returnQueue = scheduler!!.fftInputQueue,  // Reference to the buffer-pool-return queue
            fftProcessorData = appStateRepository.fftProcessorData,
            appStateRepository.waterfallSpeed.value,
            fftPeakHold = appStateRepository.fftPeakHold.value,
            suppressDcSpike = appStateRepository.suppressDcSpike.value,
            getChannelFrequencyRange = {
                val schedulerHandle = scheduler
                val demodulatorHandle = demodulator
                if(schedulerHandle != null && demodulatorHandle != null)
                    Pair(
                        schedulerHandle.channelFrequency - demodulatorHandle.channelWidth,
                        schedulerHandle.channelFrequency + demodulatorHandle.channelWidth)
                else
                    null
            },
            onAverageSignalStrengthChanged = appStateRepository.averageSignalStrength::set
        )
        fftProcessor!!.start()

        // Set state to running and hand over fft processor queues to UI
        appStateRepository.analyzerRunning.set(true)
        appStateRepository.analyzerStartPending.set(false)
        startForegroundService()
        launchSupervisionCoroutine()

        

        return true
    }

    private fun launchSupervisionCoroutine() {
        // Launch a coroutine to supervise the activity connection
        serviceScope.launch {
            var secondsSinceActivityStopped = 0
            while (appStateRepository.analyzerRunning.value) {
                delay(1000) // Checks every second
                if (!isBound)
                    secondsSinceActivityStopped += 1
                else
                    secondsSinceActivityStopped = 0

                // The LG modem capture must outlive Activity rebinding/backgrounding.  Its native
                // stream is continuous and tearing it down after five seconds looks exactly like a
                // random RF stop.  Other sources retain the upstream idle-stop behaviour.
                val keepLgCaptureAlive = source is LgModemSource
                if (secondsSinceActivityStopped > 5 && !keepLgCaptureAlive &&
                    !appStateRepository.recordingRunning.value &&
                    appStateRepository.demodulationMode.value == DemodulationMode.OFF) {
                    Log.i(TAG, "launchSupervisionCoroutine: Activity was unbound for 5 seconds. Stopping Analyzer..")
                    stopAnalyzer()
                } else if (secondsSinceActivityStopped == 6 && keepLgCaptureAlive) {
                    Log.i(TAG, "launchSupervisionCoroutine: LG capture remains active while Activity is unbound")
                }
            }
        }
    }

    private fun applyNewDemodulationMode(newDemodulationMode: DemodulationMode): Boolean {
        if(demodulator == null || source == null || scheduler == null)
            return false

        // (de-)activate demodulation in the scheduler and set the sample rate accordingly:
        if (newDemodulationMode == DemodulationMode.OFF) {
            scheduler!!.isDemodulationActivated = false
            demodulator!!.demodulationMode = DemodulationMode.OFF
            (source as? LgModemSource)?.setDemodulationContinuityMode(false)
            return true
        }


        if(!demodulator!!.supportsInputSampleRate(source!!.sampleRate)) {
            Log.i(TAG, "Source sample rate (${source!!.sampleRate} Sps) is not compatible with demodulator rate ${demodulator!!.requiredInputRate}.")
            Toast.makeText(this, "Sample rate is not supported by the demodulator", Toast.LENGTH_LONG).show()
            return false
        }
        demodulator!!.demodulationMode = newDemodulationMode
        (source as? LgModemSource)?.setDemodulationContinuityMode(true)
        scheduler!!.isDemodulationActivated = true
        demodulator!!.channelWidth = appStateRepository.channelWidth.value
        scheduler!!.squelchSatisfied = appStateRepository.squelchSatisfied.value
        scheduler!!.channelFrequency = if(newDemodulationMode == DemodulationMode.CW)
                appStateRepository.channelFrequency.value - Demodulator.CW_OFFSET_FREQUENCY
            else
                appStateRepository.channelFrequency.value
        return true
    }

    /**
     * Will create a IQ Source instance according to the user settings.
     *
     * @return true on success; false on error
     */
    private fun createSource(): Boolean {
        sourceKind = appStateRepository.sourceType.value
        when (appStateRepository.sourceType.value) {
            SourceType.LGMODEM -> source = LgModemSource(
                appStateRepository.sourceFrequency.value,
                appStateRepository.lgRfBand.value,
                appStateRepository.lgTuneMode.value,
                appStateRepository.lgUarfcn.value,
                appStateRepository.lgPllProfile.value,
                appStateRepository.lgPllMultiplier.value,
                appStateRepository.lgLnaState.value,
                appStateRepository.lgDvgaOffset.value
            ).also { it.sampleRate = appStateRepository.sourceSampleRate.value.toInt() }
            SourceType.FILESOURCE -> source =
                FileIQSource()
            
            
        }
        return true
    }

    /**
     * Will open the IQ Source instance.
     * Note: some sources need special treatment on opening, like the rtl-sdr source.
     *
     * @return true on success; false on error
     */
    private fun openSource(): Boolean {
        when (appStateRepository.sourceType.value) {
            SourceType.LGMODEM -> if (source is LgModemSource)
                return source?.open(this, iqSourceActions) == true
            else {
                Log.e(TAG, "openSource: sourceType is LG_MODEM, but source is null or of another type.")
                return false
            }

            SourceType.FILESOURCE -> if (source != null && source is FileIQSource) {
                (source as FileIQSource).init(
                    appStateRepository.filesourceUri.value.toUri(),
                    this.contentResolver,
                    appStateRepository.sourceSampleRate.value.toInt(),
                    appStateRepository.sourceFrequency.value,
                    1024*256,
                    appStateRepository.filesourceRepeatEnabled.value,
                    when (appStateRepository.filesourceFileFormat.value) {
                        FilesourceFileFormat.HACKRF, FilesourceFileFormat.CI8 -> FileIQSource.FILE_FORMAT_8BIT_SIGNED
                        FilesourceFileFormat.RTLSDR -> FileIQSource.FILE_FORMAT_8BIT_UNSIGNED
                        FilesourceFileFormat.CI16 -> FileIQSource.FILE_FORMAT_16BIT_SIGNED
                    }
                )
                return source?.open(this, iqSourceActions) == true
            } else {
                Log.e(TAG,"openSource: sourceType is FILE_SOURCE, but source is null or of other type.")
                return false
            }

        }
    }

    fun startRecording() {

        if (ReceiverFocus.paused) return
        if (appStateRepository.recordingRunning.value) {
            Log.w(TAG, "startRecording: Recording is already running. do nothing..")
            return
        }
        val activeScheduler = scheduler
        if (activeScheduler == null || !appStateRepository.analyzerRunning.value) {
            appStateRepository.emitAnalyzerEvent(
                AppStateRepository.AnalyzerEvent.SourceFailure("Start the analyzer before recording"))
            return
        }
        val recordingStartedTimestamp = System.currentTimeMillis()
        val filename = "ongoing_recording.iq"
        val openedRecording = RecordingStorage.openRecording(this, filename)
        if (openedRecording == null) {
            appStateRepository.emitAnalyzerEvent(
                AppStateRepository.AnalyzerEvent.SourceFailure("Cannot open recording on SD/external/internal storage"))
            return
        }
        val file = openedRecording.file
        val bufferedOutputStream = openedRecording.output
        Log.i(TAG, "startRecording: Opened ${file.absolutePath}")
        var maxRecordingTimeMilliseconds: Long? = null
        var maxRecordingFileSizeBytes: Long? = null
        when(appStateRepository.recordingstopAfterUnit.value) {
            StopAfterUnit.NEVER -> Unit
            StopAfterUnit.MB -> maxRecordingFileSizeBytes = appStateRepository.recordingStopAfterThreshold.value*(1024L*1024L)
            StopAfterUnit.GB -> maxRecordingFileSizeBytes = appStateRepository.recordingStopAfterThreshold.value*(1024L*1024L*1024L)
            StopAfterUnit.SEC -> maxRecordingTimeMilliseconds = appStateRepository.recordingStopAfterThreshold.value*1000L
            StopAfterUnit.MIN -> maxRecordingTimeMilliseconds = appStateRepository.recordingStopAfterThreshold.value*1000L*60L
        }
        activeScheduler.startRecording(
            bufferedOutputStream = bufferedOutputStream,
            onlyWhenSquelchIsSatisfied = appStateRepository.recordOnlyWhenSquelchIsSatisfied.value,
            maxRecordingTime = maxRecordingTimeMilliseconds,
            maxRecordingFileSize = maxRecordingFileSizeBytes,
            onRecordingStopped = { finalSize -> appStateRepository.emitAnalyzerEvent(AppStateRepository.AnalyzerEvent.RecordingFinished(finalSize, file)) },
            onFileSizeUpdate = appStateRepository.recordingCurrentFileSize::set
        )
        // update ui
        appStateRepository.recordingStartedTimestamp.set(recordingStartedTimestamp)
        appStateRepository.recordingCurrentFileSize.set(0L)
        appStateRepository.recordingRunning.set(true)
    }

    fun stopRecording() {
        scheduler?.stopRecording()
    }

    private fun handleAppStateChanges() {
        val s = serviceScope
        val asr = appStateRepository

        // Closing a selected source releases its held FTM; ordinary Stop retains it.
        s.collectAppState(asr.sourceType) {
            if (source != null && sourceKind != it) stopAnalyzer(releaseSource = true)
        }
        // source tab
        s.collectAppState(asr.sourceFrequency) { source?.frequency = it }
        s.collectAppState(asr.sourceSampleRate) { source?.sampleRate = it.toInt() }
        s.collectAppState(asr.lgRfBand) { (source as? LgModemSource)?.setRfBand(it) }
        s.collectAppState(asr.lgTuneMode) { (source as? LgModemSource)?.setTuneMode(it) }
        s.collectAppState(asr.lgUarfcn) { (source as? LgModemSource)?.setUarfcn(it) }
        s.collectAppState(asr.lgPllProfile) { (source as? LgModemSource)?.setProfileId(it) }
        s.collectAppState(asr.lgPllMultiplier) { (source as? LgModemSource)?.setPllMultiplier(it) }
        s.collectAppState(asr.lgLnaState) { (source as? LgModemSource)?.setLnaState(it) }
        s.collectAppState(asr.lgDvgaOffset) { (source as? LgModemSource)?.setDvgaOffset(it) }
        
        s.collectAppState(asr.filesourceFileFormat) { (source as? FileIQSource)?.fileFormat = it.nativeFormat }
        s.collectAppState(asr.filesourceRepeatEnabled) { (source as? FileIQSource)?.isRepeat = it }

        // view tab
        s.collectAppState(asr.fftSize) { scheduler?.fftSize = effectiveFftSize(it) }
        s.collectAppState(asr.waterfallSpeed) { fftProcessor?.waterfallSpeed = it }
        s.collectAppState(asr.fftPeakHold) { fftProcessor?.fftPeakHold = it }
        s.collectAppState(asr.suppressDcSpike) { fftProcessor?.suppressDcSpike = it }

        // demodulation tab
        s.collectAppState(asr.demodulationMode) { applyNewDemodulationMode(it) }
        s.collectAppState(asr.channelFrequency) { scheduler?.channelFrequency = if(asr.demodulationMode.value == DemodulationMode.CW) it - Demodulator.CW_OFFSET_FREQUENCY else it }
        s.collectAppState(asr.channelWidth) { demodulator?.channelWidth = it }
        s.collectAppState(asr.squelchSatisfied) { scheduler?.squelchSatisfied = it }
        s.collectAppState(asr.effectiveAudioVolumeLevel) { demodulator?.audioVolumeLevel = it }

        // settings tab
        s.collectAppState(asr.loggingEnabled) {
            if (it)
                LogcatLogger.startLogging(this@AnalyzerService)
            else if (LogcatLogger.isLogging)
                LogcatLogger.stopLogging()
        }
    }
}
