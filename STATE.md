# STATE.md — ClipCells

- date: 2026-09-11 (v0.3 + ревью, на устройстве пока v0.2)
- local: develop 3460138, dirty: нет
- remote: github.com/MikasaAckerrman/ClipCells (private), default=develop
- CI: run 34589846462 SUCCESS — ревью-фиксы; подпись стабильная (clipcells3.keystore)

## Полное ревью (2026-09-11) — итоги
Исправлено:
1. MainViewModel: pendingCount сбрасывается при неудаче startService (init + startQueue) — иначе счётчик прилипал к чужой очереди
2. Database.kt: мёртвый import ColumnInfo удалён
3. Манифест: windowSoftInputMode=adjustResize; темы: windowLightNavigationBar=false (values + v31)

Осознанные компромиссы (задокументированы, не баги):
- микро-гонка revision между getQueue и setPrimaryClip (окно мс, следующая запись затирает)
- Room-конденсация эмита: мгновенная замена A→B не показывает snackbar A (A прервана)
- микро-лосс удержания при cancel между held и onHold (повтор решает)
- удаление ячейки при активной очереди её текстов — очередь продолжит (снапшот текстов в copy_queue_items, FK нет)

Грамматика copiedText проверена на 1/2-4/11/21/101/111.
Открытые функции PRD (не дефекты): drag-перестановка сообщений/ячеек, экспорт/импорт, PIN/биометрия.

## v0.3 изменения
- Редактор: warning только при изменениях; поля фикс 56–96dp со скроллом
- Стабильная подпись через Actions secrets; разбивка ui/*; lazy PendingIntent; contentType

## На устройстве
- Установлена v0.2.0 (временная подпись). Установка v0.3: ОДНО удаление, дальше обновления встанут без удаления
- Gboard-история, удержание/выбор/Undo — ручная проверка юзером
