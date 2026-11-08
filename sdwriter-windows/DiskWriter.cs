// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
using System.Diagnostics;
using System.Security.Cryptography;

namespace D325SdWriter;

internal static class DiskWriter
{
    internal static async Task WriteAsync(string imagePath, DiskTarget target, IProgress<(long, long)> progress,
        IProgress<string> status, CancellationToken token)
    {
        target = DiskDiscovery.RequireUnchanged(target);
        await SetOfflineAsync(target.Index, true);
        try
        {
            status.Report("Writing raw image and hashing input…");
            await using var disk = new FileStream(target.DeviceId, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite,
                1024 * 1024, FileOptions.Asynchronous | FileOptions.WriteThrough);
            await using var image = new FileStream(imagePath, FileMode.Open, FileAccess.Read, FileShare.Read,
                1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            using var inputHash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[1024 * 1024];
            long done = 0;
            while (done < RawImage.Size)
            {
                var wanted = (int)Math.Min(buffer.Length, RawImage.Size - done);
                var count = await image.ReadAsync(buffer.AsMemory(0, wanted), token);
                if (count == 0) throw new EndOfStreamException("Image changed or ended during write.");
                inputHash.AppendData(buffer, 0, count);
                await disk.WriteAsync(buffer.AsMemory(0, count), token);
                done += count;
                progress.Report((done, RawImage.Size));
            }
            await disk.FlushAsync(token);
            disk.Flush(true);
            var writtenInput = Convert.ToHexString(inputHash.GetHashAndReset()).ToLowerInvariant();
            if (writtenInput != RawImage.Sha256) throw new InvalidDataException("Image changed after verification.");

            status.Report("Reading the image back and verifying SHA-256…");
            disk.Position = 0;
            var readback = await RawImage.HashAsync(disk, RawImage.Size, progress, token);
            if (readback != RawImage.Sha256) throw new IOException("SD readback SHA-256 mismatch.");

            status.Report("Moving backup GPT and expanding blank userdata…");
            disk.Position = 0;
            var front = new byte[34 * 512];
            await disk.ReadExactlyAsync(front, token);
            var sectors = checked((long)(target.Size / 512UL));
            var expanded = GptExpander.Expand(front, sectors);
            foreach (var write in expanded.Writes)
            {
                disk.Position = checked(write.Sector * 512L);
                await disk.WriteAsync(write.Data, token);
            }
            await disk.FlushAsync(token);
            disk.Flush(true);
            status.Report($"SUCCESS. userdata ends at sector {expanded.LastUsableSector}. Lineage formats blank ext4 on first boot.");
        }
        finally
        {
            // Keep the completed card offline so Windows cannot offer to format its Linux partitions.
            // Removing and reinserting the reader clears the temporary device state.
        }
    }

    private static async Task SetOfflineAsync(int index, bool offline)
    {
        var value = offline ? "$true" : "$false";
        using var process = Process.Start(new ProcessStartInfo {
            FileName = "powershell.exe",
            Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"Set-Disk -Number {index} -IsOffline {value} -ErrorAction Stop\"",
            UseShellExecute = false, RedirectStandardError = true, CreateNoWindow = true,
        }) ?? throw new IOException("Could not start PowerShell to take the SD disk offline.");
        var error = await process.StandardError.ReadToEndAsync();
        await process.WaitForExitAsync();
        if (process.ExitCode != 0) throw new IOException($"Could not take disk offline: {error}");
    }
}
