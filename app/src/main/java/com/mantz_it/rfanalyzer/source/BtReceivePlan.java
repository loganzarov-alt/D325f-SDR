// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

/** RF-to-physical tuning shared by configuration and boundary tests. */
public final class BtReceivePlan {
    private BtReceivePlan() { }
    public static long receiverFrequency(long rf, int channel) {
        if (channel < 0 || channel > 78) throw new IllegalArgumentException("BT channel");
        return rf < BtRxLimit.activeHz ? (2402L + channel) * 1_000_000L - rf : rf;
    }
    public static boolean force1000x20(long rf, long physical) {
        return rf < BtRxLimit.activeHz && physical >= 1_548_200_000L && physical <= 2_457_500_000L;
    }
}
