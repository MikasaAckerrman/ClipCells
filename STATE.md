# STATE.md — ClipCells

- date: 2026-09-11 (v0.2)
- local: develop 1a9a24e, dirty: нет
- remote: github.com/MikasaAckerrman/ClipCells (private), default=develop
- CI: run 34584034531 SUCCESS, APK v0.2.0 (11 MB, без material-icons-extended)
- build: :app:assembleDebug зелёный; :core:test 6/6 зелёный

## Проверено на устройстве (v0.2)
- Тап по ячейке «minis» (3 сообщ.) → подсказка «Скопировано 3 сообщения» в приложении ✓
- Системного уведомления при копировании НЕТ (список уведомлений пуст) ✓
- Финальный буфер = третье сообщение очереди (фрагмент протокола) ✓
- Установка: mount projectGITHUB → shizuku exec cat → /data/local/tmp → pm install (cp НЕ перезаписывает файл — использовать cat >)

## Изменения v0.2
- Уведомление только при уходе в фон (ProcessLifecycleOwner → FGS specialUse), в приложении — snackbar
- Восстановление очереди: VM init → unfinishedQueueSize → startService (продолжение с nextIndex)
- Монохром тема (чёрный #050505/белый), fixed dark; squircle percent=32; пресс-волна + scale
- values-v31 splash background #050505 → белый экран старта убран
- Убраны: material-icons-extended, vectorDrawables, ContextCompat.startForegroundService

## Не проверено
- Gboard история: все 3 записи отдельными элементами (нужен ClipCells на переднем плане + поле ввода)
- Удержание 2,7 с → окно 3×3, выбор, Undo удаления — ручные
- Мигание статус-бара: уведомление убрано (корневая причина), визуально подтвердить юзеру

## next
1. Тест Gboard-истории на устройстве.
2. Ручной прогон: удержание/выбор/Undo/фоновая очередь с уведомлением.
3. Экспорт/импорт, блокировка PIN/биометрия.
4. Список доработок от юзера (обещал скинуть).

## Инфраструктура
- git-guard запрещает push в master — работать в develop, CI на все ветки.
- KSP подключён resolutionStrategy (маркер на портале 404). Room 2.7.0 + KSP1 1.0.31 — работает.
- CI: gradle/actions/setup-gradle@v4 + temurin 21; артефакт ClipCells-debug-apk.
- Установка: cp в /var/minis/mounts/projectGITHUB → shizuku exec cat > /data/local/tmp → pm install.
