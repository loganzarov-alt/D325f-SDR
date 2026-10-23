# LG D325 device preparation: current status

The owner has a working, modified LG D325 and reported that the source-built
v20058 APK works there. This repository builds the application. It does not yet
reproduce the complete phone configuration from a stock device.

Known runtime requirements include root access, the matching LG/vendor QMI
libraries, and the device-specific kernel/modem/IQ transport modifications used
on the working phone. Minimum application API level is 25; this does not establish
compatibility with arbitrary Android ROMs or other LG/Qualcomm models.

The supplied recovery material contains kernel patch records, reconstructed
fragments and modem/DSP experiments. It does not establish a complete, tested
flashable firmware bundle. Historical commands and absolute paths are not a
verified installation guide.

Before claiming reproducibility on a second phone, record and verify:

1. Exact LG model, Android ROM/build, bootloader/root method and partition layout.
2. Hashes of the working boot/kernel and relevant firmware images, their source
   provenance, and a device-appropriate backup/restore procedure.
3. Exact kernel source commit, configuration, applied patches and build tools.
4. The required modem/IQ patches, how they are installed, and which components
   remain proprietary or cannot be redistributed.
5. A complete setup trial on a second device, followed by reception, audio,
   recording, restart/recovery and sustained-run measurements.

No flashing commands are presented here as tested instructions. The current
hardware evidence is one owner's report on an already prepared phone.
