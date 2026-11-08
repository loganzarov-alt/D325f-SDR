// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
using System.Management;

namespace D325SdWriter;

internal sealed record DiskTarget(int Index, string DeviceId, string Model, ulong Size, string Bus, string Media)
{
    public override string ToString() => $"{DeviceId} — {Size / 1_000_000_000.0:F1} GB — {Model} — {Bus}/{Media}";
}

internal static class DiskDiscovery
{
    internal static IReadOnlyList<DiskTarget> FindTargets()
    {
        var systemDisk = FindSystemDisk();
        var targets = new List<DiskTarget>();
        using var search = new ManagementObjectSearcher(
            "SELECT Index,DeviceID,Model,Size,InterfaceType,MediaType,PNPDeviceID FROM Win32_DiskDrive");
        foreach (ManagementObject disk in search.Get())
        {
            var index = Convert.ToInt32(disk["Index"]);
            var size = Convert.ToUInt64(disk["Size"] ?? 0UL);
            var bus = Convert.ToString(disk["InterfaceType"]) ?? "unknown";
            var media = Convert.ToString(disk["MediaType"]) ?? "unknown";
            var pnp = Convert.ToString(disk["PNPDeviceID"]) ?? string.Empty;
            var external = media.Contains("removable", StringComparison.OrdinalIgnoreCase) ||
                           bus.Equals("USB", StringComparison.OrdinalIgnoreCase) ||
                           pnp.StartsWith("USBSTOR", StringComparison.OrdinalIgnoreCase);
            if (!external || index == systemDisk || index == 0 || size < (ulong)RawImage.Size) continue;
            targets.Add(new DiskTarget(index, Convert.ToString(disk["DeviceID"])!,
                Convert.ToString(disk["Model"]) ?? "unknown", size, bus, media));
        }
        return targets.OrderBy(d => d.Index).ToArray();
    }

    internal static DiskTarget RequireUnchanged(DiskTarget expected) =>
        FindTargets().SingleOrDefault(d => d.Index == expected.Index && d.DeviceId == expected.DeviceId && d.Size == expected.Size)
        ?? throw new IOException("Selected removable disk disappeared or changed.");

    private static int FindSystemDisk()
    {
        var systemDrive = Path.GetPathRoot(Environment.SystemDirectory)?.TrimEnd('\\') ?? "C:";
        using var partitions = new ManagementObjectSearcher("SELECT DiskIndex,DeviceID FROM Win32_DiskPartition");
        foreach (ManagementObject partition in partitions.Get())
        {
            foreach (ManagementObject logical in partition.GetRelated("Win32_LogicalDisk"))
            {
                if (string.Equals(Convert.ToString(logical["DeviceID"]), systemDrive, StringComparison.OrdinalIgnoreCase))
                    return Convert.ToInt32(partition["DiskIndex"]);
            }
        }
        throw new InvalidOperationException("Could not identify the Windows system disk; refusing to list write targets.");
    }
}
