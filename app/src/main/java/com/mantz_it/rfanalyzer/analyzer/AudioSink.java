// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.analyzer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.mantz_it.rfanalyzer.dsp.FirFilter;
import com.mantz_it.rfanalyzer.source.SamplePacket;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Audio Sink</h1>
 *
 * Module:      AudioSink.java
 * Description: This class implements the interface to the systems audio API.
 *              It will run in a separate thread and buffer incoming sample packets
 *              in a blocking queue. Input packets are demodulated (real) signals.
 *              This class will decimate the incoming sample rate according to the
 *              audio rate.
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
public class AudioSink extends Thread {
	private AudioTrack audioTrack = null;		// AudioTrack object that is used to pass audio samples to the Android system
	private volatile boolean stopRequested = true;
	private ArrayBlockingQueue<SamplePacket> inputQueue = null;		// Queue that holds incoming samples
	private ArrayBlockingQueue<SamplePacket> outputQueue = null;	// Queue that holds available buffers
	private int packetSize;		// packet size of the incoming sample packets
	private int sampleRate;		// audio sample rate of the AudioSink
	private static final int QUEUE_SIZE = 6;	// This results in a double buffer. see Scheduler...
	private static final String LOGTAG = "AudioSink";
    private final AudioHeadroom headroom;
	private FirFilter audioFilter1 = null;		// Filter used to decimate the incoming signal rate
	private FirFilter audioFilter2 = null;		// Cascaded filter for high incoming signal rates
	private SamplePacket tmpAudioSamples;		// tmp buffer for audio filters.

	/**
	 * Constructor. Will create a new AudioSink.
	 *
	 * @param packetSize	size of the incoming packets
	 * @param sampleRate	sample rate of the audio signal
	 */
	public AudioSink (int packetSize, int sampleRate) {
		this.packetSize = packetSize;
		this.sampleRate = sampleRate;
        this.headroom = new AudioHeadroom(sampleRate);

		// Create the queues and fill them with
		this.inputQueue = new ArrayBlockingQueue<SamplePacket>(QUEUE_SIZE);
		this.outputQueue = new ArrayBlockingQueue<SamplePacket>(QUEUE_SIZE);
		for (int i = 0; i < QUEUE_SIZE; i++)
			this.outputQueue.offer(new SamplePacket(packetSize));

		// Create an instance of the AudioTrack class:
		int minimumBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
		// Give this old device roughly 200 ms of AudioTrack headroom.  The previous
		// minimum-sized buffer underruns easily when modem/Compose work steals a slice.
		int targetBufferSize = sampleRate * 4 / 5; // 0.2 s * 4 bytes/float
		int bufferSize = Math.max(minimumBufferSize, targetBufferSize);
		AudioAttributes audioAttributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
				.build();

		AudioFormat format = new AudioFormat.Builder()
				.setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
				.setSampleRate(sampleRate)
				.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
				.build();

		this.audioTrack = new AudioTrack.Builder()
				.setAudioAttributes(audioAttributes)
				.setAudioFormat(format)
				.setBufferSizeInBytes(bufferSize)
				.setTransferMode(AudioTrack.MODE_STREAM)
				.build();

		// Create the audio filters:
		this.audioFilter1 = FirFilter.createLowPass(2, 1, 1, 0.1f, 0.15f, 30);
		Log.d(LOGTAG,"constructor: created audio filter 1 with " + audioFilter1.getNumberOfTaps() + " Taps.");
		this.audioFilter2 = FirFilter.createLowPass(4, 1, 1, 0.1f, 0.1f, 30);
		Log.d(LOGTAG,"constructor: created audio filter 2 with " + audioFilter2.getNumberOfTaps() + " Taps.");
		this.tmpAudioSamples = new SamplePacket(packetSize);
	}

	/**
	 * Starts the thread
	 */
	@Override
	public synchronized void start() {
		stopRequested = false;
		super.start();
	}

	/**
	 * Stops the thread
	 */
	public void stopSink () {
		stopRequested = true;
	}

	/**
	 * @return size of the packets that are offered by getPacketBuffer()
	 */
	public int getPacketSize() {
		return packetSize;
	}

	/**
	 * The AudioSink allocates the buffers for audio playback. Use this method to request
	 * a free buffer. This method will block if no buffer is available.
	 *
	 * @param timeout	max time this method will block
	 * @return free buffer or null if no buffer available
	 */
	public SamplePacket getPacketBuffer(int timeout) {
		try {
			return outputQueue.poll(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Log.e(LOGTAG,"getPacketBuffer: Interrupted. return null...");
			return null;
		}
	}

	/**
	 * Enqueues a packet buffer for being played on the audio track.
	 *
	 * @param packet	the packet buffer from getPacketBuffer() filled with samples
	 * @return true if success, false if error
	 */
	public boolean enqueuePacket(SamplePacket packet) {
		if(packet == null) {
			Log.e(LOGTAG, "enqueuePacket: Packet is null.");
			return false;
		}
		try {
			// Audio continuity matters more than a few milliseconds of DSP latency.
			// Never silently drop a demodulated packet here.
			while (!stopRequested) {
                if (inputQueue.offer(packet, 100, TimeUnit.MILLISECONDS)) return true;
            }
            outputQueue.offer(packet);
            return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			outputQueue.offer(packet);
			return false;
		}
	}

	@Override
	public void run() {
		SamplePacket packet;
		SamplePacket filteredPacket;
		SamplePacket tempPacket = new SamplePacket(packetSize);

		this.setName("Thread-AudioSink-" + System.currentTimeMillis());
		try {
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
		} catch (Throwable ignored) { }
		Log.i(LOGTAG,"AudioSink started. (Thread: " + this.getName() + ")");

		// start audio playback:
		try {
        // The capacity alone is not a jitter reserve: prefill before play().
        headroom.begin();
        boolean playbackStarted = false;
        int primedFrames = 0;
        final int primeTarget = Math.max(1, sampleRate * 3 / 20); // 150 ms


		// Continuously write the data from the queue to the audio track:
		while (!stopRequested) {
			try {
				// Get the next packet from the queue
				packet = inputQueue.poll(50, TimeUnit.MILLISECONDS);
                headroom.update(audioTrack.getPlaybackHeadPosition());

				if(packet == null) {
                    // Do not strand a short squelch-open burst below the target.
                    if (!playbackStarted && primedFrames > 0) {
                        audioTrack.play(); playbackStarted = true;
                    }
					//Log.d(LOGTAG, "run: Queue is empty. skip this round");
					continue;
				}

				// apply audio filter (decimation)
				if(packet.getSampleRate() > this.sampleRate) {
					applyAudioFilter(packet, tempPacket);
					filteredPacket = tempPacket;
				}
				else
					filteredPacket = packet;

				// Write it to the audioTrack:
				int samplesWritten = 0;
                    long lastProgress = android.os.SystemClock.elapsedRealtime();
				while(samplesWritten < filteredPacket.size() && !stopRequested) {
					int result = audioTrack.write(filteredPacket.re(), samplesWritten,
							filteredPacket.size() - samplesWritten, AudioTrack.WRITE_NON_BLOCKING);
					if (result < 0) throw new IllegalStateException("AudioTrack write failed: " + result);
                    if (result == 0) {
                        // Device buffer can be smaller than its requested size.
                        if (!playbackStarted && primedFrames > 0) {
                            audioTrack.play(); playbackStarted = true;
                            lastProgress = android.os.SystemClock.elapsedRealtime();
                            continue;
                        }
                        if (android.os.SystemClock.elapsedRealtime() - lastProgress > 2000)
                            throw new IllegalStateException("AudioTrack stalled");
                        Thread.sleep(4);
                        continue;
                    }
                    headroom.wrote(result);
                    lastProgress = android.os.SystemClock.elapsedRealtime();
                    if (!playbackStarted) {
                        primedFrames += result;
                        if (primedFrames >= primeTarget) {
                            audioTrack.play(); playbackStarted = true;
                        }
                    }
					samplesWritten += result;
				}

				headroom.update(audioTrack.getPlaybackHeadPosition());
                // Return the buffer to the output queue
				outputQueue.offer(packet);
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "run: Interrupted while polling from queue. stop");
				stopRequested = true;
			}
		}

		// stop audio playback:
        } catch (RuntimeException e) {
            Log.e(LOGTAG, "Audio output failed", e);
        } finally {
            stopRequested = true;
            headroom.end();
            try { audioTrack.stop(); } catch (IllegalStateException ignored) { }
            audioTrack.release();
        }
		this.stopRequested = true;
		Log.i(LOGTAG,"AudioSink stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * Will filter the real array contained in input and decimate them to the audio rate.
	 *
	 * @param input		incoming (unfiltered) samples at the incoming rate (quadrature rate)
	 * @param output	outgoing (filtered, decimated) samples at audio rate
	 */
	public void applyAudioFilter(SamplePacket input, SamplePacket output) {
        output.setSize(0);
		// if we need a decimation of 8: apply first and second filter (decimate to input_rate/8)
		if(input.getSampleRate()/sampleRate == 8) {
			// apply first filter (decimate to input_rate/2)
			tmpAudioSamples.setSize(0);	// mark buffer as empty
			if (audioFilter1.filterReal(input, tmpAudioSamples, 0, input.size()) < input.size()) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter1] could not filter all samples from input packet.");
			}

			// apply second filter (decimate to input_rate/8)
			output.setSize(0);
			if (audioFilter2.filterReal(tmpAudioSamples, output, 0, tmpAudioSamples.size()) < tmpAudioSamples.size()) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter2] could not filter all samples from input packet.");
			}
		} else if (input.getSampleRate()/sampleRate == 2){
			// apply first filter (decimate to input_rate/2 )
			output.setSize(0);
			if (audioFilter1.filterReal(input, output, 0, input.size()) < input.size()) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter1] could not filter all samples from input packet.");
			}
		} else
			Log.e(LOGTAG,"applyAudioFilter: incoming sample rate is not supported!");
	}
}
