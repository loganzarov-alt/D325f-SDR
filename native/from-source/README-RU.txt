C/ASM-реконструкция lg_stream из пользовательского архива
LG-D325-lg_stream-kernel-modem-sources-20261023.tar.gz.
smem-app-stream.c SHA256 f40f123d7924d4b1a1633798948de14b65c9085b775656526b76d2b110ff82a5.
Оригинальный полный текст C не найден; это реконструкция.

APK собирается через native/build_helpers.py. Готовый базовый ELF не используется.
reference/lg_stream-rxfix3 — локальный тестовый эталон, исключён из git и APK.
Извлечь из исходного APK: python3 tools/extract-reference.py /путь/к/оригиналу.apk.
reconstruction.patch — разница со старой C-базой из предоставленного архива.

Повторить сравнительные тесты (Linux x86_64, NDK r27c, qemu-arm):
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.2.12479018
python3 native/from-source/tests/build-oracle.py
qemu-arm native/from-source/build/oracle native/from-source/reference/lg_stream-rxfix3

Тест пересобирается NDK clang и статически связывается с Bionic.
Только в тест добавлен TLS-объект с выравниванием 32 и -fno-emulated-tls,
чтобы статический ARM Bionic запускался в QEMU. Производственный C не менялся.
4272 случая: конфигурации, HDLC/CRC, consume кольца и радиоавтомат
с подменёнными DIAG/PLL/gain-вызовами. Это не проверка аппаратного приёма.
