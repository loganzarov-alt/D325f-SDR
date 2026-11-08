// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class GptExpanderTest {
    @Test
    fun expandsUserdataAndRemovesGrow() {
        val front = makeGptPrefix()
        val result = GptExpander.expand(front, 200_000, 34, 10_000)

        assertEquals(199_989, result.lastUsableSector)
        assertEquals(listOf(199_990L, 199_999L, 2L, 1L, 0L), result.writes.map { it.sector })
        val entries = result.writes[2].data
        assertEquals(Long.MIN_VALUE, entries.u64(48))
        assertEquals(10_000, entries.u64(33 * 128 + 32))
        assertEquals(199_989, entries.u64(33 * 128 + 40))
        assertTrue(entries.copyOfRange(34 * 128, 35 * 128).all { it == 0.toByte() })

        val primary = result.writes[3].data
        assertEquals(199_999, primary.u64(32))
        assertEquals(199_989, primary.u64(48))
        assertEquals(crc(entries.copyOf(36 * 128)), primary.u32(88))
        val primaryForCrc = primary.copyOf().apply { putU32(16, 0) }
        assertEquals(crc(primaryForCrc, 92), primary.u32(16))

        val backup = result.writes[1].data
        assertEquals(199_999, backup.u64(24))
        assertEquals(1, backup.u64(32))
        assertEquals(199_990, backup.u64(72))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnexpectedPartitionAfterUserdata() {
        val front = makeGptPrefix(laterName = "secrets")
        GptExpander.expand(front, 200_000, 34, 10_000)
    }

    private fun makeGptPrefix(laterName: String = "grow"): ByteArray {
        val front = ByteArray(34 * 512)
        front[510] = 0x55
        front[511] = 0xaa.toByte()
        front.putU32(458, 19_999)

        val entries = ByteArray(36 * 128)
        makeEntry(entries, 0, "modem", 34, 999)
        makeEntry(entries, 33 * 128, "userdata", 10_000, 19_000)
        makeEntry(entries, 34 * 128, laterName, 19_001, 19_900)
        entries.copyInto(front, 2 * 512)

        val header = ByteArray(512)
        "EFI PART".toByteArray().copyInto(header)
        header.putU32(8, 0x00010000)
        header.putU32(12, 92)
        header.putU64(24, 1)
        header.putU64(32, 19_999)
        header.putU64(40, 34)
        header.putU64(48, 19_990)
        repeat(16) { header[56 + it] = (it + 1).toByte() }
        header.putU64(72, 2)
        header.putU32(80, 36)
        header.putU32(84, 128)
        header.putU32(88, crc(entries))
        header.putU32(16, crc(header, 92))
        header.copyInto(front, 512)
        return front
    }

    private fun makeEntry(bytes: ByteArray, offset: Int, name: String, start: Long, end: Long) {
        repeat(16) { bytes[offset + it] = (it + 3).toByte() }
        repeat(16) { bytes[offset + 16 + it] = (it + 23).toByte() }
        bytes.putU64(offset + 32, start)
        bytes.putU64(offset + 40, end)
        name.toByteArray(Charsets.UTF_16LE).copyInto(bytes, offset + 56)
    }

    private fun crc(bytes: ByteArray, length: Int = bytes.size): Long = CRC32().run {
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
