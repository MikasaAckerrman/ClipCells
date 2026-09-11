# ClipCells — правила R8

# Схемы Room хранятся в сгенерированном коде, миграций нет (exportSchema=false, версия 1)
-keep class com.clipcells.app.data.** { *; }

# Обратные вызовы корутин и подводки жизненного цикла
-dontwarn kotlinx.coroutines.**
