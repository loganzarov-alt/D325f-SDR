<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# D325 SD Writer for Windows

The Windows x64 writer accepts only the separate raw sanitized `.img` whose
SHA-256 is hard-coded in `RawImage.cs`. It requires administrator rights,
enumerates only removable/USB disks, excludes the Windows system disk and
`PhysicalDrive0`, and requires an exact destructive confirmation string.

The program verifies the image, writes it, reads the full written range back,
verifies SHA-256 again, then relocates both GPT copies and grows partition 34
to the end of the physical card. The modem FAT partition gets GPT bit 63 so
Windows/Android should not automount it.

Windows does not create ext4. Partition 34 remains blank and the included SD
boot ramdisk's `formattable` fstab flag makes LineageOS create ext4 on first
boot. Keep the first LG boot powered for up to 20 minutes. The disk remains
offline in Windows after success; safely remove the card reader.
