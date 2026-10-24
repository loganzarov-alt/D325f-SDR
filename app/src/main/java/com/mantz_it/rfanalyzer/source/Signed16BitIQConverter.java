// SPDX-License-Identifier: GPL-3.0-or-later
// Based on RF Analyzer's Signed16BitIQConverter, Copyright (C) 2025 Dennis Mantz,
// licensed GPL-2.0-or-later (https://github.com/demantz/RFAnalyzer).
// Changes: Copyright (C) 2026 Logan Zarov. Distributed under GPL-3.0-or-later.
package com.mantz_it.rfanalyzer.source;

/** Converts little-endian interleaved signed 16-bit I/Q (CI16) samples. */
public final class Signed16BitIQConverter extends IQConverter {
    private double phase;

    @Override protected void generateLookupTable() { }
    @Override protected void generateMixerLookupTable(int ignored) { }

    private static short littleEndianShort(byte low, byte high) {
        return (short) ((low & 0xff) | (high << 8));
    }

    @Override public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket target) {
        int count = Math.min(packet.length / 4, target.capacity() - target.size());
        int start = target.size();
        for (int i = 0; i < count; i++) {
            int offset = i * 4;
            target.re()[start + i] = littleEndianShort(packet[offset], packet[offset + 1]) / 32768.0f;
            target.im()[start + i] = littleEndianShort(packet[offset + 2], packet[offset + 3]) / 32768.0f;
        }
        target.setSize(start + count);
        target.setSampleRate(sampleRate);
        target.setFrequency(frequency);
        return count;
    }

    @Override public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket target, long channelFrequency) {
        int count = Math.min(packet.length / 4, target.capacity() - target.size());
        int start = target.size();
        double phaseStep = 2.0 * Math.PI * (frequency - channelFrequency) / sampleRate;
        for (int i = 0; i < count; i++) {
            int offset = i * 4;
            float inReal = littleEndianShort(packet[offset], packet[offset + 1]) / 32768.0f;
            float inImaginary = littleEndianShort(packet[offset + 2], packet[offset + 3]) / 32768.0f;
            double cosine = Math.cos(phase);
            double sine = Math.sin(phase);
            target.re()[start + i] = (float) (inReal * cosine - inImaginary * sine);
            target.im()[start + i] = (float) (inImaginary * cosine + inReal * sine);
            phase += phaseStep;
            if (phase > Math.PI || phase < -Math.PI) phase = Math.IEEEremainder(phase, 2.0 * Math.PI);
        }
        target.setSize(start + count);
        target.setSampleRate(sampleRate);
        target.setFrequency(channelFrequency);
        return count;
    }
}
