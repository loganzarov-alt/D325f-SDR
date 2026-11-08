// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

data class BlockTarget(
    val baseName: String,
    val devicePath: String,
    val sectors: Long,
    val model: String,
    val mounted: Boolean,
) {
    val bytes: Long get() = sectors * 512L
    override fun toString(): String = "$devicePath — ${bytes / 1_000_000_000.0} GB — $model${if (mounted) " — mounted" else ""}"
}

object DeviceDiscovery {
    fun requireRootAndTools() {
        require(RootShell.run("id -u").requireSuccess("su").stdout.trim() == "0") { "su did not grant root" }
        val tools = RootShell.run(
            "for p in /system/bin/dd /system/bin/blockdev /system/bin/mke2fs /system/bin/e2fsck /system/bin/umount /system/bin/sync /system/bin/head /system/bin/sha256sum; do test -x \"\$p\" || exit 12; done"
        )
        tools.requireSuccess("Required Android filesystem tools")
    }

    fun discover(minimumBytes: Long): List<BlockTarget> {
        val names = RootShell.run("ls -1 /sys/block").requireSuccess("List block devices").stdout.lineSequence()
            .map(String::trim)
            .filter(TargetPolicy::validBaseName)
            .distinct()
            .toList()
        val mounts = RootShell.run("mount").requireSuccess("Read mounts").stdout
        return names.mapNotNull { name ->
            val removable = RootShell.run("cat /sys/block/$name/removable").requireSuccess("Read removable flag").stdout.trim()
            val deviceType = RootShell.run("cat /sys/block/$name/device/type 2>/dev/null || echo unknown")
                .requireSuccess("Read device type").stdout.trim()
            val externalSdSlot = name.startsWith("mmcblk") && deviceType == "SD"
            if (removable != "1" && !externalSdSlot) return@mapNotNull null
            val sectors = RootShell.run("cat /sys/block/$name/size").requireSuccess("Read device size").stdout.trim().toLong()
            if (sectors * 512L < minimumBytes) return@mapNotNull null
            val model = RootShell.run("cat /sys/block/$name/device/name 2>/dev/null || cat /sys/block/$name/device/model 2>/dev/null || echo unknown")
                .requireSuccess("Read device model").stdout.trim().ifBlank { "unknown" }
            val preferred = "/dev/block/$name"
            val path = if (RootShell.run("test -b $preferred").code == 0) preferred else "/dev/$name"
            if (!TargetPolicy.validDevicePath(path) || RootShell.run("test -b $path").code != 0) return@mapNotNull null
            val mounted = mounts.lineSequence().map { it.substringBefore(' ') }.any { partitionOf(it, path) }
            BlockTarget(name, path, sectors, "$model/$deviceType", mounted)
        }.sortedBy { it.devicePath }
    }

    fun unmountPartitions(target: BlockTarget) {
        require(TargetPolicy.validDevicePath(target.devicePath))
        // Android vold exposes mounted media as public:<major>,<minor>, not necessarily as sda1/mmcblk1p1.
        // Ask StorageManager to release only volumes whose sysfs device belongs to the selected disk.
        val name = target.baseName
        RootShell.run(
            "for node in /sys/block/$name/${name}*; do " +
                "test -f \"\$node/dev\" || continue; " +
                "id=\$(cat \"\$node/dev\"); " +
                "id=\${id/:/,}; " +
                "test -x /system/bin/sm && /system/bin/sm unmount public:\$id >/dev/null 2>&1 || true; " +
                "done"
        ).requireSuccess("Unmount vold volumes")
        val mounts = RootShell.run("mount").requireSuccess("Read mounts").stdout.lineSequence().mapNotNull { line ->
            val source = line.substringBefore(' ')
            val resolved = RootShell.run("readlink -f $source 2>/dev/null || echo $source").stdout.trim()
            if ((partitionOf(source, target.devicePath) || partitionOf(resolved, target.devicePath)) &&
                source.matches(Regex("[A-Za-z0-9_./:-]+"))) source else null
        }.toList().asReversed()
        for (source in mounts) {
            RootShell.run("/system/bin/umount $source").requireSuccess("Unmount $source")
        }
    }

    private fun partitionOf(source: String, basePath: String): Boolean {
        val suffix = source.removePrefix(basePath)
        if (suffix == source || suffix.isEmpty()) return false
        return if (basePath.substringAfterLast('/').startsWith("mmcblk")) {
            suffix.startsWith('p') && suffix.drop(1).all { it.isDigit() }
        } else {
            suffix.all { it.isDigit() }
        }
    }
}
