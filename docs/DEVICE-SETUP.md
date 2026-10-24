# LG D325 device setup / Подготовка устройства

## Tested device

Logan Zarov tested the published **D325f SDR 1.0.0** APK on the modified LG D325
and confirmed it works on **24 October 2026**.

Modem reception requires:

- Root access.
- The matching LG/vendor QMI libraries.
- The kernel, modem and IQ transport configuration used on the prepared phone.
- The hardware modification shown in [Hardware: B8 / B1](HARDWARE.md).

The app requires Android 7.1+ (API 25), ARMv7. The APK installs the application;
it does not install the phone's kernel or modem firmware. A complete setup
procedure from a stock phone has not yet been verified on a second device.

## По-русски

**24 октября 2026 года** Logan Zarov проверил опубликованный APK **1.0.0**
на доработанном LG D325 и подтвердил его работу.

Для приёма с модема нужны root-доступ, подходящие системные QMI-библиотеки,
подготовленные ядро, модем и транспорт IQ. Аппаратная доработка показана
на [фотографиях с обозначениями B8/B1](HARDWARE.md).

Требования приложения: Android 7.1+ (API 25), ARMv7. APK устанавливает приложение;
ядро и прошивку модема он не устанавливает. Полная процедура подготовки штатного
телефона пока не проверена на втором устройстве.

## Modem Patcher / Патчер модема

[Source and instructions](../modem-patcher/README.md) ·
[Инструкция по-русски](../modem-patcher/README.ru.md) ·
[APK and TWRP ZIPs](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/modem-patcher-v1.1.0)

The patcher is one part of preparation. It checks the LG D325 model and the six
supported modem-file hashes before applying or reversing the XOR patch.
It does not supply a kernel, root access, complete modem firmware or hardware changes.

In Android, first press **Save dump** and copy the `.bin` and `.sha256` files
from *Internal storage/D325f/* to a PC. Then use **Apply patch**; **Restore stock**
reverses it. **Restore from dump** repairs an interrupted write. Reboot is a
separate button. The TWRP Toggle ZIP saves a dump before its first write and
switches stock ↔ patched; the Restore-Dump ZIP restores the single dump placed
next to it. Unsupported firmware and mixed states are refused.

В Android сначала нажмите **Save dump** и скопируйте `.bin` и `.sha256` из
*Внутренняя память/D325f/* на ПК. Затем **Apply patch**; **Restore stock**
возвращает сток, **Restore from dump** исправляет оборванную запись.
Перезагрузка — отдельной кнопкой. TWRP-ZIP Toggle сохраняет дамп перед первой
записью и переключает сток ↔ патч; ZIP Restore-Dump восстанавливает дамп,
лежащий рядом с ним. Неподдерживаемая прошивка и смешанное состояние отклоняются.

## Boot image / TWRP

The owner supplied a 23,068,672-byte `boot.img` on 24 October 2026. Offline extraction confirms Linux 3.4.0+, build #6, GCC 16.1.0, dated 13 September 2026 12:36:46 UTC, and the D325 IQ/SMEM 471 patch strings. This identifies the embedded build; a rebuild from the published source has not yet been compared byte-for-byte, and this supplied image has not been flashed during these checks.

SHA-256 of the supplied image:

```text
d0c827f232e613bff481fcc5405cb962aaca30effc50ba21277453ab1f35ced0  boot.img
```

This is a complete boot image, including its ramdisk, for the project's LG D325 / LineageOS 14.1 setup. Compatibility with other devices or ROMs is not established. **Release upload of this binary is pending; the source archive is not a flashable image.**

In TWRP:

1. Back up **Boot** and copy the backup off the phone.
2. Select **Install → Install Image → boot.img → Boot**.
3. Swipe to flash, then reboot System. Do not select Recovery or wipe Data.
4. If it fails to boot, return to TWRP and restore your Boot backup.

The boot image does not apply the modem patch. Modem preparation and hardware changes remain separate steps.

## boot.img через TWRP

Владелец предоставил образ размером 23 068 672 байта. Из образа извлечена строка Linux 3.4.0+, сборка **#6**, GCC 16.1.0, 13.09.2026 12:36:46 UTC; найдены строки патча D325 IQ/SMEM 471. Это подтверждает идентификатор встроенной сборки, но не побайтовое соответствие опубликованным исходникам. Прошивка этого файла в рамках проверки не выполнялась.

SHA-256 указан выше. Образ включает ядро и ramdisk для конфигурации проекта LG D325 / LineageOS 14.1; совместимость с другими моделями и прошивками не установлена. **Загрузка бинарного образа в Releases пока не выполнена. Архив исходников прошивать нельзя.**

1. В TWRP сохраните резервную копию **Boot** и скопируйте её с телефона.
2. **Установка → Установка IMG → boot.img → Boot**.
3. Подтвердите прошивку свайпом и перезагрузите систему. Не выбирайте Recovery и не очищайте Data.
4. Если система не загружается, вернитесь в TWRP и восстановите копию Boot.

Патч модема и аппаратная доработка выполняются отдельно.

## Validation / Проверки

The 1.1.0 bundle reports 37 offline fixture checks: 16 host-native, 15 ARM/QEMU
and 6 installer checks. Publication separately reran 19 synthetic-image host
tests, the native SHA-256 self-test and the cache-read contract check. The APK
signature, source manifest and matching native/payload/license files were verified.
These checks do not exercise a physical modem partition or a TWRP flash.
The owner's device report in the project README is separate from these automated
results; the bundled reports record no phone installation or real modem writes.

В комплекте 1.1.0 приложены отчёты о 37 проверках на файлах-копиях: 16 на хосте,
15 в ARM/QEMU и 6 проверках установщиков. При публикации отдельно повторены
19 host-тестов на искусственных образах, самопроверка SHA-256 и проверка порядка
сброса кеша/прямого чтения. Проверены подпись APK, манифест исходников и совпадение
native-программы, патча и лицензий между исходниками, APK и ZIP.
Эти проверки не выполняют запись в реальный modem-раздел и прошивку через TWRP.
Сообщение владельца о работе инструментов в README проекта учитывается отдельно:
в приложенных автоматических отчётах установка на телефон и запись в реальный
модем не зафиксированы.
