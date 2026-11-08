// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPolicyTest {
    @Test
    fun internalEmmcIsNeverAccepted() {
        assertFalse(TargetPolicy.validBaseName("mmcblk0"))
        assertFalse(TargetPolicy.validDevicePath("/dev/block/mmcblk0"))
        assertFalse(TargetPolicy.validDevicePath("/dev/block/mmcblk0p1"))
    }

    @Test
    fun removableDeviceNamesAreStrictlyAccepted() {
        assertTrue(TargetPolicy.validBaseName("mmcblk1"))
        assertTrue(TargetPolicy.validDevicePath("/dev/block/mmcblk1"))
        assertTrue(TargetPolicy.validDevicePath("/dev/sda"))
        assertFalse(TargetPolicy.validDevicePath("/dev/block/mmcblk1;reboot"))
        assertEquals("/dev/block/mmcblk1p34", TargetPolicy.partitionPath("/dev/block/mmcblk1", 34))
        assertEquals("/dev/sda34", TargetPolicy.partitionPath("/dev/sda", 34))
    }
}
