# Выпуск обновления

Проверки выполняются до публикации. Изменение исходников само по себе не объявляет обновление готовым.

1. Увеличить versionCode/versionName соответствующих приложений; сохранить существующие Android signing identities и открытый ключ обновлений.
2. Собрать Windows EXE с .NET 3.5 references, Android Bridge и WireRoute; выполнить проверки. Не распространять изолированные `.usbtest`/`.uitest` APK.
3. Подготовить полную Windows ZIP с `USBBridge.exe` в корне и рядом `USBBridge.exe.config`, `helper`, `platform-tools`, лицензиями. Создать общий kit ZIP с папками Windows и Android.
4. В каталоге артефактов должны быть `USBBridge-Windows-VERSION.zip`, `USBBridge-Android-VERSION.apk`, `WireRoute-Android-WIREVERSION.apk`.
5. Запустить `make_update_feed.py --help`. Передать каталог артефактов, локальный закрытый ключ, новый sequence, версии, aapt2, Java и apksigner. Скрипт проверяет package/version/signature APK и подписывает конкретные размеры и SHA-256.
6. Проверить, что ключ соответствует `updates/public-key.json`. Закрытый ключ не копировать в Git, релиз или GitHub Secrets.
7. Создать draft release с тегом `vVERSION`, загрузить проверенные артефакты. Загрузить тестовые копии и сравнить SHA-256; проверить подпись файла обновления тем же кодом, что использует приложение.
8. Опубликовать release, проверить публичные URL каждого файла. Только после этого обновить `updates/stable.json` в ветке main. Этот порядок исключает предложение ещё недоступной версии.
9. Проверить production feed из Windows-программы и Android-приложения, затем зафиксировать результат проверки. Более старая версия не должна предлагаться установленной новой версии.

Файл обновлений — JSON с двумя Base64-полями `payload` и `signature`. Подпись: RSA PKCS#1 v1.5 / SHA-256 над точными байтами декодированного payload. Payload UTF-8 JSON: `schema:1`, возрастающий `sequence`, `publishedAt`, массив `releases`.

Записи: `id` равен `windows`, `androidBridge` или `androidWireRoute`; `versionCode`, `versionName`, `url`, `sha256`, `size`. Для APK дополнительно `packageName` и `signerSha256`. URL ведёт на asset этого репозитория в GitHub Releases. Публикация только для текущего проверенного канала stable.
