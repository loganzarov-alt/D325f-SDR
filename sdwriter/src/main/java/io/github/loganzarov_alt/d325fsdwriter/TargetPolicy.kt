// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

object TargetPolicy {
    private val BASE_NAME = Regex("(?:mmcblk[1-9][0-9]*|sd[a-z]+)")
    private val SAFE_PATH = Regex("/dev/(?:block/)?(?:mmcblk[1-9][0-9]*|sd[a-z]+)")

    fun validBaseName(name: String): Boolean = BASE_NAME.matches(name)

    fun validDevicePath(path: String): Boolean = SAFE_PATH.matches(path)

    fun partitionPath(devicePath: String, number: Int): String {
        require(validDevicePath(devicePath))
        require(number in 1..128)
        return if (devicePath.substringAfterLast('/').startsWith("mmcblk")) {
            "${devicePath}p$number"
        } else {
            "$devicePath$number"
        }
    }
}
