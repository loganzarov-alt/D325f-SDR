// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

import java.util.concurrent.TimeUnit

class CardWriter(
    private val archive: ImageArchive,
    private val manifest: ImageManifest,
    private val target: BlockTarget,
    private val status: (String) -> Unit,
    private val progress: (Long, Long) -> Unit,
) {
    fun writeAndExpand() {
        require(TargetPolicy.validDevicePath(target.devicePath))
        require(target.bytes >= manifest.minimumTargetBytes) { "SD card is smaller than required" }
        DeviceDiscovery.requireRootAndTools()
        val current = DeviceDiscovery.discover(manifest.minimumTargetBytes).singleOrNull {
            it.baseName == target.baseName && it.sectors == target.sectors
        } ?: error("Selected removable device disappeared or changed size")
        DeviceDiscovery.unmountPartitions(current)

        status("Writing verified compact image…")
        val dd = "/system/bin/dd of=${target.devicePath} bs=1048576 conv=fsync"
        RootShell.stream(dd) { output -> archive.copyRawTo(output, manifest, progress) }
            .requireSuccess("Write image")
        RootShell.run("/system/bin/sync").requireSuccess("Sync image")

        status("Verifying SD readback SHA-256…")
        val readbackSha = RootShell.run(
            "/system/bin/head -c ${manifest.rawSize} ${target.devicePath} | /system/bin/sha256sum"
        ).requireSuccess("Verify SD readback").stdout.trim().substringBefore(' ').lowercase()
        require(readbackSha == manifest.rawSha256) { "SD readback SHA-256 mismatch" }

        status("Expanding GPT userdata partition…")
        val prefix = RootShell.read(
            "/system/bin/dd if=${target.devicePath} bs=512 count=34 2>/dev/null",
            34 * 512,
        )
        require(prefix.size == 34 * 512) { "Short GPT readback" }
        val sectors = RootShell.run("/system/bin/blockdev --getsz ${target.devicePath}")
            .requireSuccess("Read target sector count").stdout.trim().toLong()
        require(sectors == target.sectors) { "Target size changed during write" }
        val expanded = GptExpander.expand(prefix, sectors, manifest.userdataPartition, manifest.userdataStartSector)
        for (write in expanded.writes) {
            require(write.data.size % 512 == 0)
            val count = write.data.size / 512
            RootShell.write(
                "/system/bin/dd of=${target.devicePath} bs=512 seek=${write.sector} count=$count conv=notrunc,fsync 2>/dev/null",
                write.data,
            )
        }
        RootShell.run("/system/bin/sync").requireSuccess("Sync expanded GPT")
        RootShell.run("/system/bin/blockdev --rereadpt ${target.devicePath}").requireSuccess("Reload partition table")

        val userdata = TargetPolicy.partitionPath(target.devicePath, manifest.userdataPartition)
        status("Waiting for $userdata…")
        var appeared = false
        for (attempt in 0 until 20) {
            if (RootShell.run("test -b $userdata").code == 0) {
                appeared = true
                break
            }
            TimeUnit.MILLISECONDS.sleep(500)
        }
        require(appeared) { "Expanded userdata block device did not appear" }

        val expectedBytes = (expanded.lastUsableSector - manifest.userdataStartSector + 1) * 512L
        val actualBytes = RootShell.run("/system/bin/blockdev --getsize64 $userdata")
            .requireSuccess("Read userdata size").stdout.trim().toLong()
        require(actualBytes == expectedBytes) { "userdata size mismatch: $actualBytes != $expectedBytes" }

        status("Creating clean ext4 userdata across the remaining SD space…")
        val features = "none,has_journal,ext_attr,resize_inode,filetype,extent,sparse_super,large_file,uninit_bg"
        RootShell.run(
            "/system/bin/mke2fs -F -t ext4 -b 4096 -m 0 -L userdata -O $features $userdata"
        ).requireSuccess("Format userdata")
        val check = RootShell.run("/system/bin/e2fsck -fn $userdata")
        require(check.code == 0 || check.code == 1) { "userdata filesystem check failed (${check.code}): ${check.stderr}" }
        RootShell.run("/system/bin/sync").requireSuccess("Final sync")
        status("Done. userdata uses the remaining SD card space.")
    }
}
