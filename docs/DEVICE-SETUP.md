# LG D325 device setup / Подготовка устройства

[Русский](#по-русски)

My current setup: an LG D325 (`w5ds`) with LineageOS 14.1, TWRP and root, stock
baseband **M8610B-AAAANAZM-1.0.6047**. Its internal eMMC has failed, so the
phone runs entirely from a microSD card with the same partition layout; I
flashed boot.img and the modem patch to the SD card's partitions. I confirmed on
**24 October 2026** that D325f SDR 1.0.0 and Modem Patcher 1.1.0 work on it.
SDR and live IQ worked on two LG D325f phones: my first prototype, which I later
broke with further hardware modifications, and my current phone.

The steps below are in order.

## 1. Hardware

The B8 and B1 bypass wires are brought out to the phone's 3.5 mm jack, and an
antenna is connected through an SMA adapter. Photographs, the TRS pinout and the
adapter wiring: [HARDWARE.md](HARDWARE.md).

## 2. Kernel (boot.img)

The kernel must allocate SMEM item 471 for the modem's IQ ring. Release
`kernel-6-source` has two boot images of the same kernel build; pick the one for
the storage your Android runs from:

| Image | For | Status |
| --- | --- | --- |
| [boot.img](https://github.com/loganzarov-alt/D325f-SDR/releases/download/kernel-6-source/boot.img) | a phone that runs from a **microSD card**. The eMMC controller is switched off in the device tree, so the SD card becomes `mmcblk0` | runs on my phone |
| [boot-emmc.img](https://github.com/loganzarov-alt/D325f-SDR/releases/download/kernel-6-source/boot-emmc.img) | a phone that runs from its **internal eMMC**. Same kernel and ramdisk; the eMMC controller is enabled as in LineageOS | not tested on a phone yet |

```text
d0c827f232e613bff481fcc5405cb962aaca30effc50ba21277453ab1f35ced0  boot.img
d3439492edbb9431b6ca282a954b9e1b806d6359fb04be0d1cb695395e7f5519  boot-emmc.img
```

**Do not flash boot.img on a phone that runs from eMMC.** The bootloader starts
it, but Android does not see the eMMC and does not boot; restore the Boot backup.
Both images are 23,068,672 bytes.

boot.img is the complete boot image of my phone: kernel build #6
(Linux 3.4.0+, GCC 16.1.0, 13 September 2026 12:36:46 UTC) with the
LineageOS 14.1 ramdisk. The kernel contains the D325 IQ/SMEM 471 patch strings,
and its embedded configuration is byte-identical to
[`kernel/d325f-kernel.config`](../kernel/d325f-kernel.config). Source:
[`kernel/`](../kernel/README.md). It is meant for this LineageOS 14.1 setup;
other ROMs and devices are not supported.

In TWRP:

1. Back up **Boot** and copy the backup off the phone.
2. **Install → Install Image → boot.img** (or boot-emmc.img) **→ Boot**.
3. Swipe to flash, then reboot System. Do not select Recovery or wipe Data.
4. If the phone does not boot, return to TWRP and restore the Boot backup.

> **ADB security.** This boot image is a userdebug build with
> `ro.adb.secure=0`: ADB accepts a USB connection **without the on-screen
> authorisation prompt**. Turn off USB debugging when you are not using it, and
> do not leave the phone unattended with it connected.

## 3. Modem patch

[Modem Patcher 1.1.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/modem-patcher-v1.1.0)
([instructions](../modem-patcher/README.md)) patches the phone's own modem
partition. It checks the model and the SHA-256 of the six modem files it changes
and refuses other firmware or a mixed state.

1. Install the APK and grant root.
2. **Save dump**, then copy the `.bin` and `.sha256` from *Internal storage/D325f/*
   to a PC.
3. **Apply patch**, then **Reboot phone**.
4. *Settings → About phone → Baseband version* now reads
   **P8610B-AAAANAZM-1.0.6047**.

**Restore stock** reverses the patch; **Restore from dump** repairs an
interrupted write. The TWRP Toggle ZIP does the same from recovery (it saves a
dump before its first write); the Restore-Dump ZIP restores the single dump
placed next to it.

## 4. App

Install [D325f SDR 2.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/v2.0)
(Android 7.1+, ARMv7) and grant root. It installs as an update over 1.0.0. Choose **LG D325 · SDR** as the signal source.

Below 1000 MHz (the default since 2.0, configurable) the app keys a Bluetooth carrier in the 2.4 GHz band
as the local oscillator; see [how it works](HOW-IT-WORKS.md#bluetooth-local-oscillator-below-1000-mhz).

## Validation

Modem Patcher 1.1.0 ships reports of 37 offline fixture checks (16 host-native,
15 ARM/QEMU, 6 installer). Publication reran 19 synthetic-image host tests, the
native SHA-256 self-test and the cache-read contract check, and verified the APK
signature, source manifest and the native, payload and license files shared by
source, APK and ZIPs. These automated checks do not write a physical modem
partition or flash TWRP; the device confirmation above is separate.

---

## По-русски

Моя нынешняя установка: LG D325 (`w5ds`) с LineageOS 14.1, TWRP и root,
стоковый baseband **M8610B-AAAANAZM-1.0.6047**. Внутренняя eMMC у него
неисправна, и телефон целиком работает с microSD с той же разметкой разделов;
boot.img и патч модема я ставил на разделы SD-карты. **24 октября 2026 года**
я подтвердил работу D325f SDR 1.0.0 и Modem Patcher 1.1.0.
SDR и живой поток IQ работали у меня на двух LG D325f: на первом прототипе,
который я потом вывел из строя дальнейшими доработками, и на нынешнем телефоне.

Шаги выполняются по порядку.

### 1. Железо

Обходы трактов B8 и B1 выведены на гнездо 3,5 мм, антенна подключается через
переходник SMA. Фото, распиновка TRS и схема переходника — [HARDWARE.md](HARDWARE.md).

### 2. Ядро (boot.img)

Ядро должно выделять SMEM 471 под кольцо IQ модема. В релизе `kernel-6-source`
два boot-образа одной сборки ядра; выбирайте по тому, откуда работает Android:

| Образ | Для какого телефона | Статус |
| --- | --- | --- |
| [boot.img](https://github.com/loganzarov-alt/D325f-SDR/releases/download/kernel-6-source/boot.img) | работает с **microSD**. Контроллер eMMC выключен в дереве устройств, поэтому SD-карта становится `mmcblk0` | работает на моём телефоне |
| [boot-emmc.img](https://github.com/loganzarov-alt/D325f-SDR/releases/download/kernel-6-source/boot-emmc.img) | работает со **встроенной eMMC**. То же ядро и ramdisk, контроллер eMMC включён, как в LineageOS | на телефоне пока не проверен |

**Не прошивайте boot.img на телефон, который работает с eMMC.** Загрузчик его
запустит, но Android не увидит eMMC и не загрузится — восстановите копию Boot.
Оба образа — 23 068 672 байта, SHA-256 выше.

boot.img — полный boot-образ моего телефона: ядро сборки #6 (Linux 3.4.0+,
GCC 16.1.0, 13.09.2026 12:36:46 UTC) и ramdisk LineageOS 14.1. В ядре есть строки
патча D325 IQ/SMEM 471, а встроенный в него конфиг побайтно совпадает с
[`kernel/d325f-kernel.config`](../kernel/d325f-kernel.config). Исходники —
[`kernel/`](../kernel/README.md#по-русски). Образ рассчитан на эту установку
LineageOS 14.1; другие прошивки и модели не поддерживаются.

В TWRP:

1. Сохраните резервную копию **Boot** и скопируйте её с телефона.
2. **Установка → Установка IMG → boot.img** (или boot-emmc.img) **→ Boot**.
3. Подтвердите свайпом и перезагрузите систему. Не выбирайте Recovery и не очищайте Data.
4. Если телефон не загружается, вернитесь в TWRP и восстановите копию Boot.

> **Безопасность ADB.** Это сборка userdebug с `ro.adb.secure=0`: ADB по USB
> подключается **без запроса подтверждения на экране**. Выключайте отладку по
> USB, когда она не нужна, и не оставляйте подключённый телефон без присмотра.

### 3. Патч модема

[Modem Patcher 1.1.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/modem-patcher-v1.1.0)
([инструкция](../modem-patcher/README.ru.md)) патчит собственный modem-раздел
телефона. Он проверяет модель и SHA-256 шести изменяемых файлов модема и
отказывается работать с другой прошивкой или смешанным состоянием.

1. Установите APK и разрешите root.
2. **Save dump**, затем скопируйте `.bin` и `.sha256` из *Внутренняя память/D325f/* на ПК.
3. **Apply patch**, затем **Reboot phone**.
4. В сведениях о телефоне версия baseband станет **P8610B-AAAANAZM-1.0.6047**.

**Restore stock** возвращает сток, **Restore from dump** исправляет оборванную
запись. TWRP-ZIP Toggle делает то же из recovery (перед первой записью сохраняет
дамп); ZIP Restore-Dump восстанавливает дамп, лежащий рядом с ним.

### 4. Приложение

Установите [D325f SDR 2.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/v2.0)
(Android 7.1+, ARMv7) и разрешите root. Версия ставится обновлением поверх 1.0.0. Источник сигнала — **LG D325 · SDR**.

Ниже 1000 МГц (по умолчанию с версии 2.0, настраивается) приложение включает несущую Bluetooth в диапазоне
2,4 ГГц как гетеродин — см. [как это работает](HOW-IT-WORKS.md#по-русски).

### Проверки

К Modem Patcher 1.1.0 приложены отчёты о 37 проверках на файлах-копиях (16 на хосте,
15 в ARM/QEMU, 6 установщиков). При публикации повторены 19 host-тестов на
искусственных образах, самопроверка SHA-256 и проверка сброса кеша/прямого
чтения; проверены подпись APK, манифест исходников и совпадение native-программы,
патча и лицензий между исходниками, APK и ZIP. Эти автоматические проверки не пишут
в реальный modem-раздел и не прошивают TWRP; подтверждение работы на телефоне выше —
отдельное.
