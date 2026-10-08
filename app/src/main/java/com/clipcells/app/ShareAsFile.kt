package com.clipcells.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Все сообщения сразу» для текстов БОЛЬШЕ буфера обмена.
 *
 * Буфер Android ограничен ~1МБ (binder-транзакция) — гигантская ячейка туда
 * физически не влезает, setPrimaryClip проваливается молча. Шаринг ФАЙЛОМ
 * (content:// Uri + поток) лимита не имеет: получатель читает файл напрямую.
 * Тот же паттерн, что в Copy as File.
 */
object ShareAsFile {

    /** Записывает [text] во временный .txt и открывает системный шер-шит. */
    fun share(context: Context, cellName: String, text: String) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = cellName.trim()
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            .take(40)
            .ifEmpty { "cell" }
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Старые файлы не копим: кэш чистит система, но подстрахуемся сами.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${safe}_$stamp.txt")
        try {
            file.writeText(text)
        } catch (_: Exception) {
            return
        }

        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            return
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, cellName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, cellName).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (_: Exception) {
            // Нет получателей — файл остаётся лежать в кэше, тихо уходим.
        }
    }
}
