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
