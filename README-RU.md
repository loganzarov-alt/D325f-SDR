# D325f-SDR

<img src="docs/icon.svg" width="96" alt="Иконка D325f SDR">

Экспериментальный SDR-приёмник на встроенном сотовом модеме LG D325.
Проект и интеграция модемной части — **Logan Zarov**.

[English](README.md) · [Скачать 1.0.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/v1.0.0) ·
[Сборка](docs/BUILD.md) · [Подготовка телефона](docs/DEVICE-SETUP.md) · [Аппаратная доработка: B8 и B1](docs/HARDWARE.md)

## Возможности

- Приём со встроенного модема LG и воспроизведение IQ-файлов.
- Спектр и водопад в реальном времени.
- Демодуляция AM, NFM, WFM, USB, LSB и CW.
- Запись IQ и демодулированного аудио.

IQ CI8 7,68 MS/s (около 5 МГц) прямо из baseband модема, частота задаётся патчем
прошивки модема; ниже 800 МГц гетеродином служит Bluetooth-передатчик телефона.
Тракт сигнала, характеристики и ограничения — в разделе [как это работает](docs/HOW-IT-WORKS.md#по-русски).

## Демо

[![FM, КВ 14,970 МГц, 8,035 МГц и 1875 МГц на LG D325](docs/images/demo-poster.jpg)](https://github.com/loganzarov-alt/D325f-SDR/raw/refs/heads/main/docs/media/d325f-sdr-demo.mp4)

[Видео, 36 с](https://github.com/loganzarov-alt/D325f-SDR/raw/refs/heads/main/docs/media/d325f-sdr-demo.mp4): FM-диапазон, речь на КВ 14,970 МГц,
8,035 МГц и сигнал базовых станций 1875 МГц (диапазон 1800 МГц) — всё принимает собственный модем телефона.
Разбор IQ-записей с телефона (FM со стереопилотом и RDS, аналоговое ТВ 551,25 МГц,
КВ AM) — в разделе [как это работает](docs/HOW-IT-WORKS.md#recorded-examples).
[Скачать три исходные IQ-записи](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/iq-samples-2026-10-23) (знаковый CI8, 7,68 MS/s; с SHA-256).

## Как начать

Проверенная установка: LG D325 (`w5ds`) с LineageOS 14.1, TWRP и root, стоковый
baseband M8610B-AAAANAZM-1.0.6047. Пошаговая инструкция —
[подготовка устройства](docs/DEVICE-SETUP.md#по-русски).

1. **Железо**: обходы B8/B1 выведены на гнездо 3,5 мм, антенна подключается через
   переходник SMA ([доработка](docs/HARDWARE.md)).
2. **Ядро**: прошейте [boot.img](https://github.com/loganzarov-alt/D325f-SDR/releases/download/kernel-6-source/boot.img) через TWRP
   (сборка #6, выделяет SMEM 471; [исходники](kernel/README.md#по-русски)).
3. **Модем**: в [Modem Patcher 1.1.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/modem-patcher-v1.1.0) сохраните дамп,
   скопируйте его на ПК, примените патч и перезагрузитесь ([подробно](modem-patcher/README.ru.md)).
4. **Приложение**: установите [D325f SDR 1.0.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/v1.0.0) (код версии 1,
   ARMv7, Android 7.1+) и разрешите root.

24 октября 2026 года Logan Zarov подтвердил, что приложение и инструменты модема
работают на проверенном телефоне. На втором телефоне процедура пока не повторялась.
Программные шаги обратимы: копия Boot, Restore stock или сохранённый дамп модема.

## Сборка

Linux x86_64, JDK 17, Python 3 и Android SDK:

```sh
sdkmanager "platforms;android-34" "build-tools;35.0.0" \
  "ndk;27.2.12479018" "cmake;3.22.1"
export ANDROID_HOME=/путь/к/android-sdk
./gradlew --no-daemon :app:assembleRelease :app:testReleaseUnitTest
```

APK: `app/build/outputs/apk/release/app-release.apk`.
Приложение, DSP и собственные вспомогательные программы собираются из
Java/Kotlin/C/C++/ASM. [Сборка и подпись](docs/BUILD.md) · [Проверки](validation/README.md).
Проприетарные прошивки телефона и библиотеки производителя в репозиторий не входят.

## Авторство и лицензия

Приложение использует код **RF Analyzer — Dennis Mantz и других авторов**.
При разработке кода и документации использовались инструменты ИИ.

Объединённое приложение распространяется по **GPL-3.0-or-later**. Исходные
уведомления об авторстве и лицензии сторонних компонентов сохранены:
[LICENSING.md](LICENSING.md), [LICENSE](LICENSE) и раздел лицензий в приложении.
[Происхождение кода](docs/PROVENANCE.md). Проект независимый и не связан с официальной
поддержкой LG, Qualcomm или авторов исходного приложения.
