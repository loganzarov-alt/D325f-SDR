// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
#include "aaudio_sink.h"
#include "logger.h"

AAudioSink::~AAudioSink() { stop(); }
void AAudioSink::set_samplerate(int samplerate) {
    std::lock_guard<std::mutex> lock(audio_mtx);
    if (samplerate > 0) d_samplerate = samplerate;
}
void AAudioSink::close_locked() {
    if (stream) {
        stream->requestStop();
        stream->close();
        stream.reset();
    }
}
void AAudioSink::start() {
    std::lock_guard<std::mutex> lock(audio_mtx);
    close_locked();
    oboe::AudioStreamBuilder builder;
    builder.setAudioApi(oboe::AudioApi::OpenSLES)
        ->setDirection(oboe::Direction::Output)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(1)
        ->setSampleRate(d_samplerate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    auto result = builder.openStream(stream);
    if (result == oboe::Result::OK) result = stream->requestStart();
    if (result != oboe::Result::OK) {
        logger->error("Android 7 audio start failed: %s", oboe::convertToText(result));
        close_locked();
    }
}
void AAudioSink::stop() {
    std::lock_guard<std::mutex> lock(audio_mtx);
    close_locked();
}
void AAudioSink::push_samples(int16_t *samples, int nsamples) {
    std::lock_guard<std::mutex> lock(audio_mtx);
    if (!stream || !samples || nsamples <= 0) return;
    int offset = 0;
    while (offset < nsamples) {
        auto result = stream->write(samples + offset, nsamples - offset, 100000000);
        if (!result) {
            logger->error("Android 7 audio write failed: %s", oboe::convertToText(result.error()));
            close_locked();
            return;
        }
        if (result.value() <= 0) return; // Bound a stalled output; never spin indefinitely.
        offset += result.value();
    }
}
