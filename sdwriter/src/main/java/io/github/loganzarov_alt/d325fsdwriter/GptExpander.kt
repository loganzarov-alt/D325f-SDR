// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class GptWrite(val sector: Long, val data: ByteArray)

data class ExpandedGpt(
    val lastUsableSector: Long,
    val writes: List<GptWrite>,
)

object GptExpander {
    private const val SECTOR = 512

    fun expand(
        front: ByteArray,
        targetSectors: Long,
        userdataPartition: Int,
        userdataStartSector: Long,
    ): ExpandedGpt {
        require(front.size >= 34 * SECTOR) { "GPT prefix is too short" }
        require(targetSectors > 4096) { "Target is too small" }
        val mbr = front.copyOfRange(0, SECTOR)
        require(mbr[510] == 0x55.toByte() && mbr[511] == 0xaa.toByte()) { "Protective MBR is absent" }

        val header = front.copyOfRange(SECTOR, 2 * SECTOR)
        require(header.copyOfRange(0, 8).contentEquals("EFI PART".toByteArray(Charsets.US_ASCII))) {
            "Primary GPT header is absent"
        }
        val headerSize = header.u32(12).toInt()
        require(headerSize in 92..SECTOR) { "Invalid GPT header size" }
        require(header.u64(24) == 1L) { "Unexpected primary GPT location" }
        val entriesLba = header.u64(72)
        val entryCount = header.u32(80).toInt()
        val entrySize = header.u32(84).toInt()
        require(entriesLba == 2L) { "Unexpected GPT entry-array location" }
        require(entryCount in userdataPartition..128 && entrySize == 128) { "Unsupported GPT entry structure" }

        val entriesBytes = Math.multiplyExact(entryCount, entrySize)
        val entriesSectors = (entriesBytes + SECTOR - 1) / SECTOR
        require((entriesLba + entriesSectors) * SECTOR <= front.size) { "GPT entry array is truncated" }
        val entries = front.copyOfRange((entriesLba * SECTOR).toInt(), (entriesLba * SECTOR).toInt() + entriesBytes)
        require(crc32(entries) == header.u32(88)) { "GPT entry-array checksum mismatch" }

        val headerForCheck = header.copyOf()
        val expectedHeaderCrc = headerForCheck.u32(16)
        headerForCheck.putU32(16, 0)
        require(crc32(headerForCheck, headerSize) == expectedHeaderCrc) { "Primary GPT checksum mismatch" }

        val index = userdataPartition - 1
        // Partition 1 contains modem firmware. Mark it "do not automount" so Android vold does not
        // mistake its FAT filesystem for user storage while the rest of the card is being prepared.
        require(entries.copyOfRange(0, 16).any { it.toInt() != 0 } && partitionName(entries, 0, entrySize) == "modem") {
            "GPT partition 1 is not the D325 modem partition"
        }
        val firstAttributes = entries.u64(48)
        entries.putU64(48, firstAttributes or Long.MIN_VALUE)
        val userOffset = index * entrySize
        require(entries.copyOfRange(userOffset, userOffset + 16).any { it.toInt() != 0 }) { "userdata GPT entry is empty" }
        val oldStart = entries.u64(userOffset + 32)
        require(oldStart == userdataStartSector) { "userdata start differs from manifest" }
        require(partitionName(entries, userOffset, entrySize) == "userdata") { "GPT entry is not named userdata" }

        for (later in (index + 1) until entryCount) {
            val offset = later * entrySize
            if (entries.copyOfRange(offset, offset + 16).any { it.toInt() != 0 }) {
                require(partitionName(entries, offset, entrySize) == "grow") {
                    "Partition after userdata is not the disposable grow partition"
                }
                entries.fill(0, offset, offset + entrySize)
            }
        }

        val lastLba = targetSectors - 1
        val backupEntriesLba = lastLba - entriesSectors
        val lastUsable = backupEntriesLba - 1
        require(lastUsable > userdataStartSector + 131072) { "Less than 64 MiB would remain for userdata" }
        entries.putU64(userOffset + 40, lastUsable)
        val entriesCrc = crc32(entries)

        val primary = header.copyOf()
        primary.putU64(32, lastLba)
        primary.putU64(48, lastUsable)
        primary.putU32(88, entriesCrc)
        primary.putU32(16, 0)
        primary.putU32(16, crc32(primary, headerSize))

        val backup = primary.copyOf()
        backup.putU64(24, lastLba)
        backup.putU64(32, 1L)
        backup.putU64(72, backupEntriesLba)
        backup.putU32(16, 0)
        backup.putU32(16, crc32(backup, headerSize))

        val protectiveSectors = minOf(lastLba, 0xffff_ffffL)
        mbr.putU32(458, protectiveSectors)

        val paddedEntries = ByteArray(entriesSectors * SECTOR)
        entries.copyInto(paddedEntries)
        return ExpandedGpt(
            lastUsableSector = lastUsable,
            writes = listOf(
                GptWrite(backupEntriesLba, paddedEntries),
                GptWrite(lastLba, backup),
                GptWrite(entriesLba, paddedEntries),
                GptWrite(1, primary),
                GptWrite(0, mbr),
            ),
        )
    }

    private fun partitionName(entries: ByteArray, offset: Int, entrySize: Int): String {
        val nameBytes = entries.copyOfRange(offset + 56, offset + entrySize)
        return nameBytes.toString(Charsets.UTF_16LE).trimEnd('\u0000')
    }

    private fun crc32(bytes: ByteArray, length: Int = bytes.size): Long =
        CRC32().run {
            update(bytes, 0, length)
            value
        }

    private fun ByteArray.u32(offset: Int): Long =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

    private fun ByteArray.u64(offset: Int): Long =
        ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun ByteArray.putU32(offset: Int, value: Long) {
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
    }

    private fun ByteArray.putU64(offset: Int, value: Long) {
        ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
    }
}
