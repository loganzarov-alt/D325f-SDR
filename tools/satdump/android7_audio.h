// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
#pragma once
#include "common/audio/audio_sink.h"
#include <cstring>
#include <oboe/Oboe.h>
#include <mutex>

// Retain the upstream class/registry ID so existing decoder audio paths keep working.
class AAudioSink : public audio::AudioSink {
    int d_samplerate = 48000;
    std::mutex audio_mtx;
    std::shared_ptr<oboe::AudioStream> stream;
    void close_locked();
public:
    AAudioSink() = default;
    ~AAudioSink();
    void set_samplerate(int samplerate) override;
    void start() override;
    void stop() override;
    void push_samples(int16_t *samples, int nsamples) override;
    static std::string getID() { return "rtaudio"; }
    static std::shared_ptr<audio::AudioSink> getInstance() { return std::make_shared<AAudioSink>(); }
};
