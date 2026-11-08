// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
using System.Buffers.Binary;
using System.Text;

namespace D325SdWriter;

internal sealed record GptWrite(long Sector, byte[] Data);
internal sealed record ExpandedGpt(long LastUsableSector, IReadOnlyList<GptWrite> Writes);

internal static class GptExpander
{
    private const int Sector = 512;

    internal static ExpandedGpt Expand(byte[] front, long targetSectors)
    {
        if (front.Length < 34 * Sector) throw new InvalidDataException("GPT prefix is too short.");
        if (front[510] != 0x55 || front[511] != 0xaa) throw new InvalidDataException("Protective MBR is absent.");
        var mbr = front[..Sector].ToArray();
        var header = front[Sector..(2 * Sector)].ToArray();
        if (!header.AsSpan(0, 8).SequenceEqual("EFI PART"u8)) throw new InvalidDataException("Primary GPT is absent.");
        var headerSize = checked((int)U32(header, 12));
        if (headerSize is < 92 or > Sector || U64(header, 24) != 1 || U64(header, 72) != 2)
            throw new InvalidDataException("Unsupported GPT header.");
        var count = checked((int)U32(header, 80));
        var entrySize = checked((int)U32(header, 84));
        if (count < RawImage.UserdataPartition || count > 128 || entrySize != 128)
            throw new InvalidDataException("Unsupported GPT entries.");
        var entriesBytes = checked(count * entrySize);
        var entrySectors = (entriesBytes + Sector - 1) / Sector;
        var entries = front.AsSpan(2 * Sector, entriesBytes).ToArray();
        if (Crc32(entries) != U32(header, 88)) throw new InvalidDataException("GPT entry checksum mismatch.");
        var checkHeader = header.ToArray();
        var expectedHeaderCrc = U32(checkHeader, 16);
        PutU32(checkHeader, 16, 0);
        if (Crc32(checkHeader.AsSpan(0, headerSize)) != expectedHeaderCrc)
            throw new InvalidDataException("GPT header checksum mismatch.");

        if (Name(entries, 0, entrySize) != "modem") throw new InvalidDataException("Partition 1 is not D325 modem.");
        PutU64(entries, 48, U64(entries, 48) | (1UL << 63));
        var userOffset = (RawImage.UserdataPartition - 1) * entrySize;
        if (Name(entries, userOffset, entrySize) != "userdata" || U64(entries, userOffset + 32) != (ulong)RawImage.UserdataStartSector)
            throw new InvalidDataException("Partition 34 is not the expected D325 userdata.");
        for (var later = RawImage.UserdataPartition; later < count; later++)
        {
            var offset = later * entrySize;
            if (IsZero(entries.AsSpan(offset, 16))) continue;
            if (Name(entries, offset, entrySize) != "grow") throw new InvalidDataException("Unexpected partition after userdata.");
            entries.AsSpan(offset, entrySize).Clear();
        }

        var lastLba = targetSectors - 1;
        var backupEntries = lastLba - entrySectors;
        var lastUsable = backupEntries - 1;
        if (lastUsable <= RawImage.UserdataStartSector + 131_072) throw new InvalidDataException("Less than 64 MiB for userdata.");
        PutU64(entries, userOffset + 40, checked((ulong)lastUsable));
        var entriesCrc = Crc32(entries);
        var primary = header.ToArray();
        PutU64(primary, 32, checked((ulong)lastLba));
        PutU64(primary, 48, checked((ulong)lastUsable));
        PutU32(primary, 88, entriesCrc);
        PutU32(primary, 16, 0);
        PutU32(primary, 16, Crc32(primary.AsSpan(0, headerSize)));
        var backup = primary.ToArray();
        PutU64(backup, 24, checked((ulong)lastLba));
        PutU64(backup, 32, 1);
        PutU64(backup, 72, checked((ulong)backupEntries));
        PutU32(backup, 16, 0);
        PutU32(backup, 16, Crc32(backup.AsSpan(0, headerSize)));
        PutU32(mbr, 458, checked((uint)Math.Min(lastLba, uint.MaxValue)));
        var padded = new byte[entrySectors * Sector];
        entries.CopyTo(padded, 0);
        return new ExpandedGpt(lastUsable, new[] {
            new GptWrite(backupEntries, padded), new GptWrite(lastLba, backup),
            new GptWrite(2, padded), new GptWrite(1, primary), new GptWrite(0, mbr),
        });
    }

    private static string Name(byte[] entries, int offset, int entrySize) =>
        Encoding.Unicode.GetString(entries, offset + 56, entrySize - 56).TrimEnd('\0');
    private static uint U32(byte[] data, int offset) => BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(offset, 4));
    private static ulong U64(byte[] data, int offset) => BinaryPrimitives.ReadUInt64LittleEndian(data.AsSpan(offset, 8));
    private static void PutU32(byte[] data, int offset, uint value) => BinaryPrimitives.WriteUInt32LittleEndian(data.AsSpan(offset, 4), value);
    private static void PutU64(byte[] data, int offset, ulong value) => BinaryPrimitives.WriteUInt64LittleEndian(data.AsSpan(offset, 8), value);
    private static uint Crc32(ReadOnlySpan<byte> data)
    {
        uint crc = uint.MaxValue;
        foreach (var value in data) { crc ^= value; for (var bit = 0; bit < 8; bit++) crc = (crc >> 1) ^ ((crc & 1) != 0 ? 0xedb88320u : 0); }
        return ~crc;
    }
    private static bool IsZero(ReadOnlySpan<byte> data)
    {
        foreach (var value in data) if (value != 0) return false;
        return true;
    }
}
