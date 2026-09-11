# STATE.md — ClipCells

- date: 2026-09-11
- local: master 351db2a, develop 9a0031f (HEAD), dirty: нет
- remote: github.com/MikasaAckerrman/ClipCells (private), default=develop
- CI: run 34576280745 SUCCESS (core tests + assembleDebug), APK 17 MB артефакт
- build: :app:assembleDebug зелёный; :core:test 6/6 зелёный

## Готово (фундамент)
Модели+CopyPlanFactory (:core, 6 тестов), Room (cells/messages/copy_queue), CellRepository,
CopyService (specialUse FGS, очередь в БД, revision), MainViewModel, Compose UI:
главная сетка 2×3 (настройки 2–5×3–8), редактор (имя, сообщения, интервал 0,3–3 с),
удержание 2,7 с + прогресс, выбор сообщений 3×3 с нумерацией, карандаш/корзина,
удаление с подтверждением и Undo 5 с, экспорт/импорт — НЕТ (не начат), блокировка — НЕТ.

## Проверено на устройстве (iQOO Neo 10 / OriginOS 6)
- APK установлен через Shizuku pm install (uid 2000, юзер подтверждает диалог).
- Приложение запускается, редактор работает, ячейка сохранена юзером («текст», 2 сообщ.) — юзер подтвердил: работает.
- Сеть: нет INTERNET permission (манифест проверен), allowBackup=false.

## Не проверено (блокеры следующего захода)
- Фоновая очередь: FGS-уведомление «N из M» после сворачивания НЕ тестировалось.
- Сохранение промежуточных записей в истории Gboard НЕ тестировалось.
- OriginOS убивает фоновый процесс без FGS (наблюдалось) — FGS должен это лечить, проверить.
- Удержание 2,7 с, выбор 3×3, Undo, экспорт/импорт — ручные тесты не пройдены.

## next
1. Тест очереди в фоне (3 сообщения, свернуть, буфер после).
2. Удержание/выбор/Undo ручным прогоном.
3. Экспорт/импорт (модуль в репо), блокировка PIN/биометрия.
4. Полировка анимаций и цветов, иконка приложения.

## Инфраструктура
- Сборка только CI (Android SDK в PRoot нет). gradle/actions/setup-gradle@v4 + temurin 21.
- git-guard запрещает push в master — работать в develop, CI на все ветки.
- KSP подключён resolutionStrategy на symbol-processing-gradle-plugin (маркер на портале 404).
- Установка: cp в /var/minis/mounts/projectGITHUB → shizuku exec cp /data/local/tmp → pm install.
- APK копия: /var/minis/mounts/projectGITHUB/clipcells-app-debug.apk
