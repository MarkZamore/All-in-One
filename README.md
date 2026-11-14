# All in One [Modded One Block]

Подготовка сборки 1.5.4b для LANMinecraft. [Авторский клиентский выпуск](https://www.curseforge.com/minecraft/modpacks/all-in-one-modded-one-block/files/4735035)

`tools/upstream-client-lock.json` фиксирует SHA256 и размер оригинального ZIP, ссылки на официальный источник, записи зависимостей и SHA256 файлов внутри авторских overrides. Minecraft и загрузчик взяты из оригинального манифеста. Java в `portable-pack.json` соответствует выбору текущего лаунчера для этой версии Minecraft и проверяется при запуске.

## Восстановление авторского клиента

Закреплённый авторский клиент CurseForge использует project ID 439293 и file ID 4735035.
Для подготовки текущего кандидата из корня LANMinecraft запустите:

```powershell
python -B tools/PackData/CurseForge/release_overlay.py stage --pack-source LocalSources/All-in-One --output $stage
```

`tools/overlay.json` сохраняет политику открытия мира через Steam. Для нового авторского выпуска снимите отдельную квитанцию через `prepare.py --client-only` и проверьте её командой `release_overlay.py review-update`. При изменении защищённого входа пересмотрите рецепт и закрепите новые точные байты. После нативной проверки сохраните полный `tools/upstream-lock.json` и выполните `release_overlay.py verify` по готовому манифесту.

## Публикация

Для Java 8 сборка поставляет адаптер скинов в `lanminecraft/legacy-skins.jar`. Он читает реестр, который лаунчер уже передаёт игре, и оставляет Steam и UUID под управлением штатных компонентов. Сборка JAR выполняется через `tools/skin-agent/Build.ps1` с JDK 25.0.2 и исходниками LANMinecraft `cbff8c0`. Адаптер принимает локальный URL только для скина из реестра. `tools/skin-agent/stage_update.py` собирает адресное обновление из зафиксированного публичного манифеста и JAR с проверенной контрольной суммой.

All Rights Reserved. Версия в manifest.json записана автором как 1.5.4.

Пользователь подтвердил наличие разрешения на перезалив. Публикация следует после проверки полного состава и нативной приёмки.
