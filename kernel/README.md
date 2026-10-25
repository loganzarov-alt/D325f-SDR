# Kernel changes / Изменения ядра

[Русский](#по-русски)

D325f SDR runs on the LineageOS 14.1 kernel for the LG D325 with a small patch.
This folder is the corresponding source for that kernel: the patch against the
public LineageOS tree, the full kernel configuration and the build settings.

| | |
| --- | --- |
| Base tree | [LineageOS/android_kernel_lge_msm8226](https://github.com/LineageOS/android_kernel_lge_msm8226), branch cm-14.1 |
| Base commit | `bf756279493c2cc4656b784afa29822b30838e98` (Linux 3.4.0) |
| Patch | [`d325f-kernel.patch`](d325f-kernel.patch), 9 files |
| Configuration | [`d325f-kernel.config`](d325f-kernel.config), full `.config` (machine `MSM8X10_W5`) |
| Binary | [`boot.img`](https://github.com/loganzarov-alt/D325f-SDR/releases/download/kernel-6-source/boot.img) in release [`kernel-6-source`](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/kernel-6-source); flashing: [device setup](../docs/DEVICE-SETUP.md#2-kernel-bootimg) |
| License | GPL-2.0-only, as the Linux kernel |

## What the patch changes

| File | Change |
| --- | --- |
| `arch/arm/mach-msm/smem.c` | Allocates SMEM item **471** (128 KiB) at boot and clears it. The patched modem firmware writes its IQ ring there. This is the change D325f SDR needs. |
| `arch/arm/mach-msm/pil-msa.c` | After the modem image is authenticated, asks TrustZone (SCM `MP_ASSIGN`) to share the physical range `0x05000000`+64 MiB between Android and the modem. On the test phone TrustZone does not offer this call (`D325 IQ assign_mem unavailable: 0`), so nothing is shared. |
| `arch/arm/mach-msm/peripheral-loader.c` | Accepts a modem ELF segment that only describes that range (no image bytes): no memory-overlap error, no clearing, no PMI length update. Inactive with the current modem patch, whose image has no such segment. |
| `arch/arm/include/asm/uaccess.h`, `include/linux/compiler-gcc4.h`, new `compiler-gcc16.h`, `scripts/dtc/dtc-lexer.*`, `scripts/gcc-wrapper.py` | Build fixes for a modern GCC and Python 3. No runtime change. |

The `pil-msa.c` and `peripheral-loader.c` parts come from an earlier experiment
that placed the IQ ring in reserved memory. The current modem patch uses only
the SMEM ring.

## Build

```sh
sh prepare-source.sh            # fetch base, apply patch, copy .config
cd android_kernel_lge_msm8226
make ARCH=arm CROSS_COMPILE=arm-linux-gnueabi- oldconfig
make ARCH=arm CROSS_COMPILE=arm-linux-gnueabi- \
     KAFLAGS=-fno-pie KCFLAGS='-std=gnu89 -fno-pie -Wno-error' zImage dtbs
```

The published kernel was built with ARM GCC 16.1.0 and these flags; use your
toolchain's prefix for `CROSS_COMPILE`.

Layout of the published `boot.img`, for repacking with `mkbootimg`:

| | |
| --- | --- |
| Page size | 2048 |
| Base / ramdisk offset / tags offset | `0x00000000` / `0x01000000` / `0x00000100` |
| Device tree | Qualcomm QCDT `dt.img` (18 entries; device trees from the kernel's `arch/arm/boot/dts/msm8610-*w5n*`) |
| Command line | `console=ttyHSL0,115200,n8 androidboot.console=ttyHSL0 user_debug=31 msm_rtb.filter=0x37 androidboot.hardware=qcom androidboot.selinux=enforcing buildvariant=userdebug` |
| Ramdisk | LineageOS 14.1 (Android 7.1.2, ramdisk build date 17 February 2018), userdebug, `ro.adb.secure=0` |

## Identity of the published boot.img

- Kernel version string: `Linux version 3.4.0+ (root@kali) (gcc version 16.1.0 (Debian 16.1.0-3)) #6 SMP PREEMPT Sun Sep 13 12:36:46 UTC 2026`.
- The kernel contains the strings added by this patch (`LG D325 IQ SMEM ready id=471`, `D325 IQ assign_mem unavailable`, `D325 modem IQ extent` and others).
- The configuration embedded in the kernel (IKCONFIG) is **byte-identical** to
  [`d325f-kernel.config`](d325f-kernel.config).
- The test phone runs the same build: identical `/proc/version`, the kernel log
  line `pil-q6v5-mss fc880000.qcom,mss: D325 IQ assign_mem unavailable: 0`, and
  SMEM item 471 `allocated=1 size=131072` during reception.

The patch was reconstructed from the project's work history. It applies cleanly
to the base commit, whose eight affected files match their recorded SHA-256; CI
repeats the check. A kernel rebuilt from this folder has not been compared
byte-for-byte with the published image.

## Corresponding source (GPL-2.0)

The complete corresponding source of the kernel in `boot.img` is the base commit
above with [`d325f-kernel.patch`](d325f-kernel.patch) applied and
[`d325f-kernel.config`](d325f-kernel.config) as `.config`.
[`prepare-source.sh`](prepare-source.sh) reproduces exactly that tree, and a
self-contained archive of it, `D325f-SDR-kernel-6-full-source.tar.xz`, is
attached to the
[`kernel-6-source`](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/kernel-6-source)
release next to `boot.img`.

The ramdisk in `boot.img` is the LineageOS 14.1 userspace of the tested phone
(init, adbd, healthd, SELinux policy, init scripts). It contains no D325f-specific
scripts or root tools. These components are under Apache-2.0 and other AOSP
licenses; their source is in the LineageOS cm-14.1 repositories. See
[LICENSING.md](../LICENSING.md).

---

## По-русски

D325f SDR работает на ядре LineageOS 14.1 для LG D325 с небольшим патчем.
Здесь — исходник этого ядра по GPL: патч к публичному дереву LineageOS, полный
конфиг и параметры сборки. База: `LineageOS/android_kernel_lge_msm8226`,
ветка cm-14.1, коммит `bf756279…` (Linux 3.4.0). Готовый
[boot.img](https://github.com/loganzarov-alt/D325f-SDR/releases/download/kernel-6-source/boot.img)
лежит в релизе `kernel-6-source`; прошивка — в
[подготовке устройства](../docs/DEVICE-SETUP.md#2-ядро-bootimg).

Что меняет патч:
- **smem.c** — при загрузке выделяет SMEM **471** (128 КиБ); туда пропатченный
  модем пишет кольцо IQ. Это то, что нужно D325f SDR.
- **pil-msa.c** — после проверки образа модема просит TrustZone открыть
  диапазон `0x05000000`+64 МиБ одновременно Android и модему. На тестовом
  телефоне этот вызов недоступен, поэтому ничего не открывается.
- **peripheral-loader.c** — принимает сегмент ELF модема, описывающий только
  этот диапазон. С текущим патчем модема не срабатывает.
- Остальное — правки для сборки современным GCC и Python 3.

Части pil-msa.c и peripheral-loader.c остались от раннего опыта с кольцом в
зарезервированной памяти; текущий патч модема использует только SMEM.

Сборка — команды выше (`prepare-source.sh`, затем `make`), GCC 16.1.0 и флаги
`KAFLAGS=-fno-pie KCFLAGS='-std=gnu89 -fno-pie -Wno-error'`. Параметры упаковки
boot.img (`mkbootimg`) — в таблице выше.

Опубликованный boot.img: ядро сборки #6 от 13.09.2026 12:36 UTC (GCC 16.1.0) со
строками этого патча; встроенный в ядро конфиг **побайтно совпадает** с
`d325f-kernel.config`. Тестовый телефон работает на той же сборке: тот же
`/proc/version`, строка `D325 IQ assign_mem unavailable: 0` в журнале ядра и
SMEM 471 `allocated=1 size=131072` во время приёма. Пересборка из этой папки
побайтно с образом не сравнивалась.

**Исходный код по GPL-2.0.** Полный исходник ядра из boot.img — базовый коммит с
патчем `d325f-kernel.patch` и конфигом `d325f-kernel.config`; `prepare-source.sh`
воспроизводит именно это дерево, а его архив `D325f-SDR-kernel-6-full-source.tar.xz`
приложен к релизу `kernel-6-source` рядом с boot.img.

Ramdisk в boot.img — пользовательская часть LineageOS 14.1 с тестового телефона
(init, adbd, healthd, политика SELinux, init-скрипты), без скриптов и root-утилит
D325f. Эти компоненты под Apache-2.0 и другими лицензиями AOSP; их исходники — в
репозиториях LineageOS cm-14.1.
