# D325f SDR 2.0

[Русский](#по-русски)

Version 2.0, version code 20000. It is signed with the same certificate as 1.0.0,
so it installs as an update over 1.0.0 without uninstalling it.

## What is in 2.0

- Local reception, spectrum, waterfall and demodulation.
- IQ recording and file playback. The spectrum, waterfall and audio keep running
  while a recording is in progress.
- Freeze (in Settings) pauses only the spectrum and waterfall; reception, audio
  and a running recording continue.
- Inversion switch for the waterfall and recordings.
- Scheduled automations that run with the screen off.
- Reception recovery after failures; zeros fill the recording for the gap.
- Temperature control and configurable actions after an automation.
- LNA, DVGA and Bluetooth settings; the default Bluetooth limit is 1000 MHz.
- Translations (13 languages), language selection and saved settings.

The app has no network features (server, network client, streaming), and I am
not adding them: while Bluetooth runs as a continuous carrier, Wi-Fi on the LG
does not work properly. The APK requests no network permissions.

## IQ orientation

The inversion switch is saved, enabled by default and locked while a capture or
recording is running. It is combined with the frequency reversal that the
Bluetooth downconversion already causes. Orientation is applied once, to the
signed CI8 bytes, before the data goes to the display, demodulator and recorder;
the DSP no longer applies a second conjugation. The operation exchanges I and Q
(`j · conj(z)`): it reverses frequency without clipping the −128 value. File
playback does not invert recordings that are already corrected.

## Automations

A task stores its local start time, duration, centre frequency and a snapshot of
the gain, profile, bandwidth, inversion and Bluetooth settings. Overlapping tasks
are rejected. Start-up uses an exact alarm, and capture runs in a foreground
service with a partial wake lock. A task missed while the phone was off is
skipped; running tasks and temperature monitoring do not resume after a reboot.
Only one capture owns the modem at a time; if it is busy, the new task is marked
skipped.

No file is created before the first real IQ packet. Once recording has started,
an outage is written as zero CI8 samples in the same file, and the end time does
not move. Recovery repeats ordinary starts and switches ONLINE → FACTORY after two
failed starts. Any real packet resets the five-minute failure deadline. If a
failure is still unresolved when that deadline expires, the phone powers off,
even if the task has already ended. This applies only to automated capture.

After capture stops and the recording is closed, an optional reboot runs first.
Otherwise optional temperature monitoring reads the battery and CPU sensors:
a 3 °C drop on all sensors within ten minutes ends monitoring; no cooling powers
the phone off after ten minutes; reaching 70 °C after capture powers it off at
once. Temperature power-off is not used while a task is running. With no sensors
available the phone reboots. Manual cancellation cancels all following power
actions and temperature monitoring. Readings are kept in a size-limited log,
`automation-temperature.csv`, in the app's private storage.

The recording folder can be chosen from the storage list or entered by hand; a
chosen folder never silently falls back to another storage device. Automated
recordings use the selected rate, 7.68, 2.56 or 1.28 MS/s (lower rates through
the native FIR filter), and appear in Recordings. Manual raw recording stays at
7.68 MS/s with the corrected orientation.

## Bluetooth and language

The default transmit power level is 7; saved choices are kept. At levels 8–9 the
app shows a warning about the 10 mW limit. Below 100 MHz a channel above 30 is
reset to 0 before tuning; channel 30 is still allowed. Automation snapshots
include the Bluetooth channel and power. The default Bluetooth limit is 1000 MHz;
a limit you saved yourself is kept.

The language follows the system, or can be chosen by hand: English, Russian,
Spanish, Portuguese, French, German, Italian, Simplified and Traditional Chinese,
Japanese, Korean, Arabic and Hindi, with right-to-left layout for Arabic. The
choice also applies to the built-in manual. Technical identifiers, file names,
original license texts and low-level diagnostics are not translated. The
translations are generated from `tools/localization`, and CI checks that the
generated files match.

## Tests

CI runs host tests for the automation decisions (outage timer, cooling deadlines,
sensor loss, overlapping tasks), for zero-filled gaps (no file before data, no
rewinding, cancellation, full storage) and for IQ orientation (known positive and
negative offsets, all direct/Bluetooth and inversion combinations, the −128 value,
double application), and for recording with the display running (the recorded
bytes match the source, a packet is not reused before the display has read it,
Freeze does not stop a recording). These tests do not replace a check on the phone: to confirm
the orientation, receive a known transmitter both above and below the tuned
centre, directly and through the Bluetooth LO.

---

## По-русски

Версия 2.0, код версии 20000. Подписана тем же сертификатом, что 1.0.0, поэтому
ставится обновлением поверх 1.0.0, без удаления старой версии.

**Что есть в 2.0:** локальный приём, спектр, водопад и демодуляция; запись IQ и
воспроизведение файлов, причём спектр, водопад и звук работают и во время записи;
Freeze (в настройках) останавливает только спектр и водопад, приём, звук и
идущая запись продолжаются; переключатель инверсии для водопада и записей;
автоматизации по расписанию с работой при погашенном экране; восстановление
приёма при сбоях (нули в записи на время разрыва); контроль температуры и
заданные действия после автоматизации; настройки LNA, DVGA и Bluetooth, граница
Bluetooth по умолчанию — 1000 МГц; переводы (13 языков), выбор языка и сохранение
настроек.

Сетевых функций (сервер, сетевой клиент, стриминг) в приложении нет, и я их не
делаю: пока Bluetooth работает как непрерывная несущая, Wi-Fi на LG нормально не
работает. Сетевых разрешений приложение не запрашивает.

**Ориентация IQ.** Переключатель инверсии сохраняется, по умолчанию включён и
блокируется во время приёма и записи. Он учитывает разворот частоты, который уже
даёт преобразование через Bluetooth. Ориентация применяется один раз к байтам
CI8, до водопада, демодулятора и записи; повторного сопряжения в DSP больше нет.
Уже исправленные записи при воспроизведении повторно не переворачиваются.

**Автоматизации.** Задача хранит время начала, длительность, частоту и снимок
настроек (усиление, профиль, полоса, инверсия, Bluetooth). Пересекающиеся задачи
не принимаются. Файл не создаётся до первого настоящего пакета IQ; разрыв после
начала записи заполняется нулями в том же файле, время окончания не сдвигается.
Если сбой не устранён за пять минут, телефон выключается — только при
автоматической записи. После записи по выбору: перезагрузка или контроль
температуры (охлаждение на 3 °C за десять минут — контроль снимается; нет
охлаждения — выключение через десять минут; 70 °C — выключение сразу). Ручная
отмена отменяет все последующие действия.

**Bluetooth.** Уровень мощности по умолчанию — 7, на уровнях 8–9 приложение
предупреждает о пределе 10 мВт. Ниже 100 МГц канал выше 30 сбрасывается на 0.

**Проверка.** Тесты CI проверяют логику автоматизаций, нули в записи, ориентацию
IQ и запись при работающем водопаде, но не заменяют проверку на телефоне: примите известный передатчик выше и ниже
центра частоты — напрямую и через Bluetooth-гетеродин.
