// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
#pragma once
#include <cstddef>
#include <cstdint>
#include <utility>
inline void invertCi8Spectrum(int8_t* bytes, size_t length) {
    for(size_t i=0;i+1<length;i+=2) std::swap(bytes[i],bytes[i+1]);
}
