# D325f SDR 1.0.1 — IQ orientation correction

## Изменения

- Исправлена ориентация IQ в тракте приёма LG. Спектр, водопад и демодуляция используют согласованный разворот комплексных отсчётов.
- Исправление применяется как в штатной полосе 7,68 MS/s, так и в режимах 2,56 и 1,28 MS/s. Для демодуляции согласован знак частотного переноса.
- В настройках источника LG оставлен переключатель «Инверсия IQ», включённый по умолчанию. Для возврата к ориентации версии 1.0.0 остановите приём и выключите его.
- Сырые IQ-записи и воспроизведение ранее записанных файлов не изменены. Исправление действует при живом приёме с LG.
- Версия 1.0.1, versionCode 2, прежний applicationId `io.github.loganzarov_alt.d325fsdr`. Android 7.1+, ARMv7.

## Проверка и границы результата

26 октября 2026 года Logan Zarov подтвердил, что отдельная тестовая сборка с этой коррекцией исправила отображение частот на его модифицированном LG. Улучшение приёма и отчётливости водопада он наблюдал одновременно и в старой версии, поэтому это улучшение не приписывается данной коррекции: одно комплексное сопряжение зеркалит спектр, но не увеличивает его SNR. Сам APK 1.0.1 требует отдельной проверки установки; подтверждение относится к алгоритму в тестовой сборке. Полный диапазон частот и другие телефоны этим сообщением не проверены.

Коррекция меняет ориентацию IQ и выбор спектрального участка при демодуляции. Усиление LNA/DVGA, RF-настройка модема, Bluetooth-гетеродин, прошивка и ядро не менялись. Измеренный прирост чувствительности или SNR в дБ не заявляется.

Автоматические проверки включают положительные и отрицательные частотные смещения синтетических CI8-сигналов, оба направления IQ, выбор ориентации для прямого и BT-приёма, непрерывность аудио и упаковку native-компонентов. Это не заменяет аппаратные измерения.

## Установка

APK подписывается тем же release-ключом, что и версия 1.0.0; перед публикацией сравниваются сертификаты и проверяется подпись. Рабочую версию 1.0.0 можно обновить обычной установкой APK. Отдельное приложение D325f IQ Test не заменяется этой установкой.

## English

Corrects LG live-input IQ orientation consistently across the spectrum, waterfall and demodulator, including reduced-bandwidth paths. The IQ inversion switch defaults to the corrected orientation; disabling it restores 1.0.0 orientation. Raw IQ recording and file playback are unchanged. The owner confirmed corrected frequency display with the equivalent test build on one modified LG D325. A simultaneous reception improvement also occurred in the old version and is not attributed to this fix; no calibrated sensitivity/SNR gain is claimed. Version 1.0.1 uses the original package ID. The release APK uses the original 1.0.0 signing certificate for an in-place update.
