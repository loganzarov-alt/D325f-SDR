// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
using System.Security.Cryptography;

namespace D325SdWriter;

internal static class RawImage
{
    internal const long Size = 2_295_862_272L;
    internal const string Sha256 = "1c99b5dcf07d72e0c617e3e2f6a8e1ab18892e2eada5d404c0494d35e4656a6e";
    internal const long UserdataStartSector = 4_353_024L;
    internal const int UserdataPartition = 34;

    internal static async Task VerifyAsync(string path, IProgress<(long, long)> progress, CancellationToken token)
    {
        var info = new FileInfo(path);
        if (info.Length != Size) throw new InvalidDataException($"Image size is {info.Length}, expected {Size} bytes.");
        await using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        var actual = await HashAsync(input, Size, progress, token);
        if (!actual.Equals(Sha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("SHA-256 is not the published sanitized D325 image.");
    }

    internal static async Task<string> HashAsync(Stream input, long length, IProgress<(long, long)> progress, CancellationToken token)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var buffer = new byte[1024 * 1024];
        long done = 0;
        while (done < length)
        {
            var wanted = (int)Math.Min(buffer.Length, length - done);
            var count = await input.ReadAsync(buffer.AsMemory(0, wanted), token);
            if (count == 0) throw new EndOfStreamException("Image or disk read ended early.");
            hash.AppendData(buffer, 0, count);
            done += count;
            progress.Report((done, length));
        }
        return Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
    }
}
