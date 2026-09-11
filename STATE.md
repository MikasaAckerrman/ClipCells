# STATE.md — ClipCells

- date: 2026-09-11 (v0.3, на устройстве пока v0.2)
- local: develop efe6085, dirty: нет
- remote: github.com/MikasaAckerrman/ClipCells (private), default=develop
- CI: run 34588682204 SUCCESS (workflow_dispatch) — v0.3.0 APK с СТАБИЛЬНОЙ подписью
- APK артефакт: 11 022 815 байт, md5 38c9144c…

## v0.3 изменения
- Редактор: подтверждение потери данных только при реальных изменениях (name/messages/interval против исходника)
- Поля сообщений: фикс высота 56–96dp, min 2 / max 3 строки, внутренний скролл длинного текста
- Стабильная подпись: PKCS12 key clipcells3.keystore → секреты CLIPCELLS_KEYSTORE_B64 + CLIPCELLS_STORE_PASSWORD (GitHub Actions); gradle CLIPCELLS_STORE_FILE/PASSWORD, exists()-guard, debug+release
- Разбивка UI: ui/HomeMode.kt, ui/Theme.kt, ui/Cells.kt (EmptyState+CellCard, HOLD_TO_OPEN_MILLIS), ui/EditorDialogs.kt (+copiedText)
- Перф/чистка: lazy PendingIntent в CopyService, contentType у grid-итемов, убран мёртвый junit в :app, файл MainActivity 567 → 4 модуля

## На устройстве
- Установлена v0.2.0 (временная подпись из CI debug) — ПОСТАВИТЬ v0.3 только после удаления (смена подписи); последующие обновления встанут поверх без удаления
- Gboard-история (3 отдельные записи) — не проверена; удержание/выбор/Undo — не проверены юзером

## Инфраструктура (проверено)
- Установка: mounts/projectGITHUB → shizuku exec `cat src > dst` (cp не перезаписывает) → pm install
- Секреты: gh secret set только через `< файл` (пайп `printf | gh --input -` падает молча)
- keytool НЕ перезаписывает существующий PKCS12 — генерировать в новый файл
- Мёртвые ключи: keystore/clipcells.keystore и clipcells2.keystore (пароль потерян, пустые) — удалить вручную юзером
- git-guard: push только в develop; CI на все ветки
