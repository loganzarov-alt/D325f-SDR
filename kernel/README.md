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
| Configuration | [`d325f-kernel.config`](d325f-kernel.config), full `.config` of the installed kernel (machine `MSM8X10_W5`) |
| License | GPL-2.0-only, as the Linux kernel |

## What the patch changes

| File | Change |
| --- | --- |
| `arch/arm/mach-msm/smem.c` | Allocates SMEM item **471** (128 KiB) at boot and clears it. The patched modem firmware writes its IQ ring there. This is the change D325f SDR needs. |
| `arch/arm/mach-msm/pil-msa.c` | After the modem image is authenticated, asks TrustZone (SCM `MP_ASSIGN`) to share the physical range `0x05000000`+64 MiB between Android and the modem. |
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

The installed kernel was built with ARM GCC 16.1.0 and these flags; use your
toolchain's prefix for `CROSS_COMPILE`. Pack the
result into a boot image with your stock ramdisk and device tree.

## Status

- The patch was reconstructed from the project's work history. It applies
  cleanly to the base commit (checked 2026-10-24 against the upstream files;
  CI repeats the check), and all eight base files match the recorded SHA-256.
- A kernel has not yet been rebuilt from this folder and compared with the
  installed boot image, and the boot-image packaging script is not published yet.
- No kernel binary is distributed here.

---

## По-русски

D325f SDR работает на ядре LineageOS 14.1 для LG D325 с небольшим патчем.
Здесь — исходник этого ядра по GPL: патч к публичному дереву LineageOS, полный
конфиг и параметры сборки. База: `LineageOS/android_kernel_lge_msm8226`,
ветка cm-14.1, коммит `bf756279…` (Linux 3.4.0).

Что меняет патч:
- **smem.c** — при загрузке выделяет SMEM **471** (128 КиБ); туда пропатченный
  модем пишет кольцо IQ. Это то, что нужно D325f SDR.
- **pil-msa.c** — после проверки образа модема просит TrustZone открыть
  диапазон `0x05000000`+64 МиБ одновременно Android и модему.
- **peripheral-loader.c** — принимает сегмент ELF модема, описывающий только
  этот диапазон. С текущим патчем модема не срабатывает.
- Остальное — правки для сборки современным GCC и Python 3.

Части pil-msa.c и peripheral-loader.c остались от раннего опыта с кольцом в
зарезервированной памяти; текущий патч модема использует только SMEM.

Сборка — команды выше (`prepare-source.sh`, затем `make`), GCC 16.1.0 и флаги
`KAFLAGS=-fno-pie KCFLAGS='-std=gnu89 -fno-pie -Wno-error'`.

Статус: патч восстановлен по истории работы и чисто ложится на базовый коммит
(проверено 24.10.2026, CI повторяет проверку). Сборка из этой папки ещё не
сравнивалась с установленным boot.img; скрипт упаковки boot.img пока не
опубликован. Готовое ядро здесь не распространяется.
