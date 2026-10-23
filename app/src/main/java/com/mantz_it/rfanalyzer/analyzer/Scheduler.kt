// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.source.IQSourceInterface
import com.mantz_it.rfanalyzer.source.LgModemSource
import com.mantz_it.rfanalyzer.source.SamplePacket
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - Scheduler</h1>
 *
 * Module:      Scheduler.kt
 * Description: This Thread is responsible for forwarding the samples from the input hardware
 * to the Demodulator and to the Processing Loop and at the correct speed and format.
 * Sample packets are passed to other blocks by using blocking queues. The samples passed
 * to the Demodulator will be shifted to base band first.
 * If the Demodulator or the Processing Loop are to slow, the scheduler will automatically
 * drop incoming samples to keep the buffer of the source from beeing filled up.
 *
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
class Scheduler(var fftSize: Int, private val source: IQSourceInterface) : Thread() {

    companion object {
        // Define the size of the fft output and input Queues. By setting this value to 2 we basically end up
        // with double buffering. Maybe the two queues are overkill, but it works pretty well like this and
        // it handles the synchronization between the scheduler thread and the processing loop for us.
        // Note that setting the size to 1 will not work well and any number higher than 2 will cause
        // higher delays when switching frequencies.
        private const val FFT_QUEUE_SIZE = 2
        private const val DEMOD_QUEUE_SIZE = 16
        // About 3.3 seconds of LG CI8 at 7.68 MSps (32 KiB packets).  The queue
        // stores source-owned packets without copying; the writer returns each packet
        // to the source only after the bytes have reached BufferedOutputStream.
        private const val RECORDING_QUEUE_PACKETS = 1536
        private const val LOGTAG = "Scheduler"
    }

    val fftOutputQueue: ArrayBlockingQueue<SamplePacket> = ArrayBlockingQueue(FFT_QUEUE_SIZE)     // Queue that delivers samples to the Processing Loop
    val fftInputQueue: ArrayBlockingQueue<SamplePacket> = ArrayBlockingQueue(FFT_QUEUE_SIZE)      // Queue that collects used buffers from the Processing Loop
    val demodOutputQueue: ArrayBlockingQueue<SamplePacket> = ArrayBlockingQueue(DEMOD_QUEUE_SIZE) // Queue that delivers samples to the Demodulator block
    val demodInputQueue: ArrayBlockingQueue<SamplePacket> = ArrayBlockingQueue(DEMOD_QUEUE_SIZE)  // Queue that collects used buffers from the Demodulator block
    // The modem returns only 256 complex samples per request. Accumulate those
    // blocks so the filters and AudioTrack receive useful packet sizes.
    val demodPacketSize: Int = if (source is LgModemSource) 4096 + (source.packetSize / source.bytesPerSample + 29) / 30 else maxOf(source.packetSize / source.bytesPerSample, 16_384)
    @Volatile var channelFrequency: Long = 0 // Shift frequency to this value when passing packets to demodulator
    @Volatile var isDemodulationActivated: Boolean = false // Indicates if samples should be forwarded to the demodulator queues or not.
    @Volatile var squelchSatisfied: Boolean = false // indicates whether the current signal is strong enough to cross the squelch threshold

    @Volatile private var stopRequested = true

    // Recording. Raw recording is intentionally isolated from FFT/demod work.  While a
    // packet belongs to recordingQueue it is NOT returned to the IQ source, so it cannot
    // be overwritten before the dedicated writer has consumed it.
    @Volatile private var stopRecording = false
    @Volatile private var recordingAccepting = false
    @Volatile private var recordingWriterRunning = false
    @Volatile private var bufferedOutputStream: BufferedOutputStream? = null
    @Volatile private var recordedFileSize: Long = 0
    private var recordingAcceptedSize: Long = 0
    private var recordedStartTimestamp: Long = 0
    private var maxRecordingTime: Long? = null
    private var maxRecordingFileSize: Long? = null
    private var onlyWhenSquelchIsSatisfied: Boolean = false
    private var onRecordingStopped: ((finalSize: Long) -> Unit)? = null
    private var onFileSizeUpdate: ((currentFileSize: Long) -> Unit)? = null
    private val recordingQueue = ArrayBlockingQueue<ByteArray>(RECORDING_QUEUE_PACKETS)
    @Volatile private var recordingWriterThread: Thread? = null

    init {
        // allocate the buffer packets.
        for (i in 0 until FFT_QUEUE_SIZE) fftInputQueue.offer(
            SamplePacket(
                fftSize
            )
        )
        for (i in 0 until DEMOD_QUEUE_SIZE) demodInputQueue.offer(
            SamplePacket(demodPacketSize)
        )
    }

    fun stopScheduler() {
        finishRecordingAsync()
        this.stopRequested = true
        ReceiverFocus.audioActive = false
        source.stopSampling()
    }

    override fun start() {
        this.stopRequested = false
        super.start()
    }

    /**
     * Will stop writing samples to the bufferedOutputStream and close it.
     */
    fun stopRecording() {
        this.stopRecording = true
        finishRecordingAsync()
        Log.i(LOGTAG, "stopRecording: writer will drain queued IQ before closing")
    }

    @Synchronized
    fun startRecording(bufferedOutputStream: BufferedOutputStream,
                       onlyWhenSquelchIsSatisfied: Boolean,
                       maxRecordingTime: Long? = null,
                       maxRecordingFileSize: Long? = null,
                       onRecordingStopped: (finalSize: Long) -> Unit,
                       onFileSizeUpdate: (currentFileSize: Long) -> Unit) {
        if (recordingWriterRunning || recordingAccepting) {
            Log.w(LOGTAG, "startRecording: writer already active")
            return
        }
        // A previous writer should have drained this queue.  Be defensive and return
        // anything left over before starting a new session.
        var leftover = recordingQueue.poll()
        while (leftover != null) {
            source.returnPacket(leftover)
            leftover = recordingQueue.poll()
        }

        stopRecording = false
        recordedFileSize = 0
        recordingAcceptedSize = 0
        recordedStartTimestamp = System.currentTimeMillis()
        this.onlyWhenSquelchIsSatisfied = onlyWhenSquelchIsSatisfied
        this.maxRecordingTime = maxRecordingTime
        this.maxRecordingFileSize = maxRecordingFileSize
        this.onRecordingStopped = onRecordingStopped
        this.onFileSizeUpdate = onFileSizeUpdate
        this.bufferedOutputStream = bufferedOutputStream
        recordingAccepting = true
        recordingWriterRunning = true
        (source as? LgModemSource)?.setLosslessConsumerMode(true)

        val writer = Thread({ recordingWriterLoop(bufferedOutputStream) },
            "Thread-IQ-Recorder-" + System.currentTimeMillis())
        recordingWriterThread = writer
        writer.start()
        Log.i(LOGTAG, "startRecording: exclusive lossless recording started; queue=$RECORDING_QUEUE_PACKETS")
    }

    @Synchronized
    private fun finishRecordingAsync() {
        if (!recordingAccepting && !recordingWriterRunning) return
        recordingAccepting = false
        recordingWriterRunning = false
        (source as? LgModemSource)?.setLosslessConsumerMode(false)
    }

    private fun recordingWriterLoop(stream: BufferedOutputStream) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) { }
        var lastUiUpdateMs = 0L
        try {
            while (recordingWriterRunning || recordingQueue.isNotEmpty()) {
                val packet = try {
                    recordingQueue.poll(250, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } ?: continue

                try {
                    stream.write(packet)
                    recordedFileSize += packet.size.toLong()
                } finally {
                    // Ownership returns to the source only after write() has consumed bytes.
                    source.returnPacket(packet)
                }

                val now = System.currentTimeMillis()
                if (now - lastUiUpdateMs >= 500L) {
                    onFileSizeUpdate?.invoke(recordedFileSize)
                    lastUiUpdateMs = now
                }
            }
        } catch (e: IOException) {
            Log.e(LOGTAG, "recording writer failed: ${e.message}", e)
            recordingAccepting = false
            recordingWriterRunning = false
        } finally {
            // If storage failed, release any source buffers that could not be written.
            var packet = recordingQueue.poll()
            while (packet != null) {
                source.returnPacket(packet)
                packet = recordingQueue.poll()
            }
            try {
                stream.close()
            } catch (e: IOException) {
                Log.e(LOGTAG, "recording close failed: ${e.message}")
            }
            bufferedOutputStream = null
            recordingAccepting = false
            recordingWriterRunning = false
            (source as? LgModemSource)?.setLosslessConsumerMode(false)
            onFileSizeUpdate?.invoke(recordedFileSize)
            Log.i(LOGTAG, "recording writer stopped: bytes=$recordedFileSize")
            onRecordingStopped?.invoke(recordedFileSize)
        }
    }

    override fun run() {
        this.name = "Thread-Scheduler-" + System.currentTimeMillis()
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) }
        catch (_: SecurityException) { }
        Log.i(LOGTAG, "Scheduler started. (Thread: " + this.name + ")")
        Log.i(LOGTAG, "run: FFT Queues: $fftOutputQueue , $fftInputQueue")
        var fftBuffer: SamplePacket? = null
        var demodBuffer: SamplePacket? = null

        val nsPerPacket = (source.packetSize / source.bytesPerSample) * 1_000_000_000f / (if (source is LgModemSource) LgModemSource.SAMPLE_RATE else source.sampleRate)

        source.startSampling()
        var audioPriority = false
        var measuredPackets = 0
        var measuredAudioSamples = 0L
        var bandwidthNs = 0L
        var mixerNs = 0L
        var queueNs = 0L
        var fftFeedNs = 0L
        var audioDrops = 0
        var measureStart = System.nanoTime()
        var cpuStart = android.os.Debug.threadCpuTimeNanos()
        while (!stopRequested) {
            if (audioPriority != isDemodulationActivated) {
                audioPriority = isDemodulationActivated
                try { android.os.Process.setThreadPriority(if (audioPriority) -4 else android.os.Process.THREAD_PRIORITY_DEFAULT) }
                catch (_: SecurityException) { }
            }
            // Finish an FFT window before trimming backlog, otherwise large narrowband
            // FFTs could be reset forever before enough contiguous samples arrive.
            (source as? LgModemSource)?.setLiveWindowInProgress((fftBuffer?.size() ?: 0) > 0)
            val packet = source.getPacket(1000)
            if (packet == null) {
                Log.e(LOGTAG, "run: No more packets from source. Shutting down...")
                this.stopScheduler()
                break
            }
            if (source is LgModemSource && source.consumeDiscontinuity()) {
                // Never combine samples from opposite sides of a queue discontinuity.
                fftBuffer?.setSize(0)
                demodBuffer?.setSize(0)
            }
            val startTimestamp = System.nanoTime()

            // A stop request closes admission immediately; the writer keeps draining packets
            // that were already accepted.
            if (stopRecording) finishRecordingAsync()

            if (ReceiverFocus.paused && recordingAccepting) stopRecording()

            ///// Recording ////////////////////////////////////////////////////////////////////////
            var packetOwnedByRecorder = false
            if (recordingAccepting && (squelchSatisfied || !onlyWhenSquelchIsSatisfied)) {
                // Do not drop a recording packet.  If the SD card stalls, this blocks only the
                // scheduler.  LG's source is simultaneously in lossless/back-pressure mode and
                // the native helper still has its own stage ring behind us.
                try {
                    recordingQueue.put(packet)
                    packetOwnedByRecorder = true
                    recordingAcceptedSize += packet.size.toLong()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    stopRequested = true
                }

                val now = System.currentTimeMillis()
                if (maxRecordingTime?.let { it <= now - recordedStartTimestamp } == true) {
                    Log.i(LOGTAG, "run: Max Recording Time reached")
                    stopRecording = true
                    finishRecordingAsync()
                }
                if (maxRecordingFileSize?.let { it <= recordingAcceptedSize } == true) {
                    Log.i(LOGTAG, "run: Max Recording File Size reached")
                    stopRecording = true
                    finishRecordingAsync()
                }

                if (packetOwnedByRecorder) {
                    // Recording is deliberately exclusive on this old device.  Do not spend CPU
                    // on FFT, waterfall or audio while raw IQ continuity is the active job.
                    val processingTime = System.nanoTime() - startTimestamp
                    GlobalPerformanceData.updateLoad("Scheduler", processingTime / nsPerPacket)
                    continue
                }
            }

            var stageStart = System.nanoTime()
            (source as? LgModemSource)?.prepareBandwidth(packet)
            bandwidthNs += System.nanoTime() - stageStart
            ReceiverFocus.audioActive = isDemodulationActivated && (squelchSatisfied || ReceiverFocus.frozen.value)
            ///// Demodulation /////////////////////////////////////////////////////////////////////
            if (isDemodulationActivated && (squelchSatisfied || ReceiverFocus.frozen.value)) {
                // Keep the RF source fixed.  The demodulation marker is a DSP-only tuner:
                // LgModemSource translates the selected channel in native code before
                // anti-alias decimation, so dragging the marker must never retune RF.
                if (demodBuffer == null) {
                    stageStart = System.nanoTime()
                    demodBuffer = try {
                        // Demodulated audio is a continuous time stream.  Wait for a
                        // returned buffer instead of throwing this raw packet away.
                        // A stalled audio consumer must not hold the RF/FFT producer.
                        // Allow bounded scheduling jitter without immediately dropping sound.
                        // A genuinely dead consumer still cannot stop RF acquisition indefinitely.
                        demodInputQueue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        stopRequested = true
                        null
                    }
                    queueNs += System.nanoTime() - stageStart
                    if (demodBuffer == null) audioDrops++
                    demodBuffer?.setSize(0)
                }
                if (demodBuffer != null) {
                    stageStart = System.nanoTime()
                    measuredAudioSamples += source.mixPacketIntoSamplePacket(packet, demodBuffer, channelFrequency)
                    mixerNs += System.nanoTime() - stageStart
                    if (demodBuffer.size() >= (if (source is LgModemSource) 4096 else demodBuffer.capacity())) {
                        // Never drop a completed demodulation block. Back-pressure is
                        // intentional: LG source continuity mode buffers upstream while the
                        // audio pipeline catches up.
                        stageStart = System.nanoTime()
                        try {
                            if (!demodOutputQueue.offer(demodBuffer, 100, TimeUnit.MILLISECONDS)) {
                                audioDrops++
                                demodInputQueue.offer(demodBuffer)
                            }
                            demodBuffer = null
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            demodInputQueue.offer(demodBuffer)
                            demodBuffer = null
                            stopRequested = true
                        }
                        queueNs += System.nanoTime() - stageStart
                    }
                }
            } else if (demodBuffer != null) {
                demodBuffer.setSize(0)
            }

            stageStart = System.nanoTime()
            ///// FFT //////////////////////////////////////////////////////////////////////////////
            if (ReceiverFocus.paused) {
                fftBuffer?.let { it.setSize(0); fftInputQueue.offer(it) }
                fftBuffer = null
            } else {
            if (fftBuffer == null) {
                fftBuffer = fftInputQueue.poll()
                if (fftBuffer != null) {
                    if (fftBuffer.capacity() == fftSize) fftBuffer.setSize(0)
                    else fftBuffer = SamplePacket(fftSize)
                }
            }
            if (fftBuffer != null) {
                source.fillPacketIntoSamplePacket(packet, fftBuffer)
                if (fftBuffer.capacity() == fftBuffer.size()) {
                    if (!fftOutputQueue.offer(fftBuffer)) fftInputQueue.offer(fftBuffer)
                    fftBuffer = null
                }
            }

            }

            fftFeedNs += System.nanoTime() - stageStart
            source.returnPacket(packet)
            measuredPackets++
            val now = System.nanoTime()
            if (now - measureStart >= 2_000_000_000L) {
                val cpuNow = android.os.Debug.threadCpuTimeNanos()
                Log.i(LOGTAG, "IQ timing ms: elapsed=${(now-measureStart)/1000000} cpu=${(cpuNow-cpuStart)/1000000}" +
                    " packets=$measuredPackets audio_samples=$measuredAudioSamples bandwidth=${bandwidthNs/1000000}" +
                    " mix=${mixerNs/1000000} queue_wait=${queueNs/1000000} fft_feed=${fftFeedNs/1000000}" +
                    " audio_drops=$audioDrops demod_ready=${demodOutputQueue.size} demod_free=${demodInputQueue.size}")
                measureStart=now; cpuStart=cpuNow; measuredPackets=0; measuredAudioSamples=0
                bandwidthNs=0; mixerNs=0; queueNs=0; fftFeedNs=0; audioDrops=0
            }

            val processingTime = System.nanoTime() - startTimestamp
            GlobalPerformanceData.updateLoad("Scheduler", processingTime / nsPerPacket)
        }

        (source as? LgModemSource)?.releaseBandwidth()
        this.stopRequested = true
        finishRecordingAsync()
        try {
            recordingWriterThread?.join(30_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Log.i(LOGTAG, "Scheduler stopped. (Thread: " + this.name + ")")
    }

}
