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
[Инструкция по-русски](../modem-patcher/README.ru.txt) ·
[APK and TWRP ZIP](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/modem-patcher-v1.0.4)

The patcher is one part of preparation. It checks the LG D325 model and the six
supported modem-file hashes before applying or reversing the XOR patch.
It does not supply a kernel, root access, complete modem firmware or hardware changes.

В Android сначала нажмите **«Проверить»**: это чтение без записи.
Для подходящего модема доступны **«Пропатчить модем»** и **«Восстановить сток»**.
ZIP в TWRP переключает состояние при каждом применении: сток → патч → сток.
При неподдерживаемой версии или смешанном состоянии запись отклоняется.

Перед записью сохраните собственный бэкап modem. Обратный патч восстанавливает
поддерживаемые файлы без него, но отключение питания может сорвать запись и откат.
Телефон должен быть холоднее 48 °C; перед записью проверяется порог 58 °C.
Перезагрузка выполняется отдельно.

**Статус:** приложенные отчёты подтверждают чтение реального modem-раздела,
запись в файлы-копии и тесты ARM/QEMU. Проверка прошивки TWRP ZIP и записи
в реальный раздел пока не подтверждена. Дополнительные 13 host-тестов прошли
на искусственных FAT-образах. Успешный тест SDR-приложения 1.0.0 не заменяет
проверку патчера.
