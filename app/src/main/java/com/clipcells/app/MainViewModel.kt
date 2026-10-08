package com.clipcells.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipcells.app.data.CellDraft
import com.clipcells.app.data.CellRepository
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.ClipCellsDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TooLargeClipException(val chars: Int, val text: String) :
    IllegalStateException("Текст $chars симв. не влезает в буфер обмена (лимит Android ~1МБ)") {

    /** Короткий человеческий текст для снекбара. */
    val userMessage: String
        get() = "Текст ячейки — ${chars} симв., буфер Android держит ~1МБ — «Поделиться» отправит файлом"
}

private const val TAG = "ClipCellsCopy"

/**
 * Безопасный предел текста для буфера: binder-транзакция клипа ограничена ~1МБ;
 * UTF-16 в посылке = 2 байта на символ + конверт — 400К симв. даёт запас.
 */
const val CLIP_SAFE_CHARS = 400_000

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CellRepository(ClipCellsDatabase.get(application))
    private var lastDeleted: List<CellWithMessages> = emptyList()

    val cells = repository.observeCells().map<List<CellWithMessages>, List<CellWithMessages>?> { it }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    fun save(draft: CellDraft, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { repository.save(draft) }) }
    }

    /**
     * v0.13: тап = ВСЯ ячейка одним куском в буфер, мгновенно. Никаких очередей
     * и счётчиков. Прямая запись из открытого (сфокусированного) приложения —
     * легальный путь на Android 10+.
     *
     * Глубокая причина бага «копирует только одно»: буфер обмена Android
     * ограничен ~1МБ на binder-транзакцию. Гигантский текст проходит в
     * setPrimaryClip БЕЗ исключения, но запись тихо проваливается — и вставка
     * отдаёт ПРЕДЫДУЩИЙ клип (одно сообщение). Поэтому: пред-проверка размера,
     * затем верификация записи чтением, и честный фидбек при провале.
     */
    fun copyWhole(cellId: Long, onCopied: (Int, String) -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val (text, count) = repository.cellContent(cellId)
                if (text.length > CLIP_SAFE_CHARS) {
                    android.util.Log.i(TAG, "copyWhole cell=$cellId messages=$count chars=${text.length} TOO_LARGE (buffer ~1MB)")
                    throw TooLargeClipException(text.length, text)
                }
                clipboard().setPrimaryClip(ClipData.newPlainText("ClipCells", text))
                val written = clipboard().primaryClip
                    ?.getItemAt(0)?.coerceToText(getApplication())?.toString()
                if (written != text) {
                    // Запись не прошла (лимит системы) — буфер отдаёт старый клип.
                    android.util.Log.i(TAG, "copyWhole cell=$cellId VERIFY_FAILED chars=${text.length}")
                    throw TooLargeClipException(text.length, text)
                }
                android.util.Log.i(TAG, "copyWhole cell=$cellId messages=$count chars=${text.length} verified")
                count to text
            }.onSuccess { (c, t) -> onCopied(c, t) }.onFailure(onError)
        }
    }

    /** Выбранные сообщения — одним куском, в порядке выбора. */
    fun copySelected(cellId: Long, selectedIds: List<Long>, onCopied: (Int, String) -> Unit, onError: (Throwable) -> Unit) {
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val (text, count) = repository.selectedContent(cellId, selectedIds)
                if (text.length > CLIP_SAFE_CHARS) {
                    android.util.Log.i(TAG, "copySelected cell=$cellId messages=$count chars=${text.length} TOO_LARGE (buffer ~1MB)")
                    throw TooLargeClipException(text.length, text)
                }
                clipboard().setPrimaryClip(ClipData.newPlainText("ClipCells", text))
                val written = clipboard().primaryClip
                    ?.getItemAt(0)?.coerceToText(getApplication())?.toString()
                if (written != text) {
                    android.util.Log.i(TAG, "copySelected cell=$cellId VERIFY_FAILED chars=${text.length}")
                    throw TooLargeClipException(text.length, text)
                }
                android.util.Log.i(TAG, "copySelected cell=$cellId messages=$count chars=${text.length} verified")
                count to text
            }.onSuccess { (c, t) -> onCopied(c, t) }.onFailure(onError)
        }
    }

    private fun clipboard(): ClipboardManager =
        getApplication<Application>().getSystemService(ClipboardManager::class.java)

    fun delete(ids: Set<Long>, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.delete(ids) }
                .onSuccess { lastDeleted = it; onDone() }
                .onFailure(onError)
        }
    }

    fun undoDelete(onError: (Throwable) -> Unit) {
        val snapshot = lastDeleted
        if (snapshot.isEmpty()) return
        lastDeleted = emptyList()
        viewModelScope.launch { runCatching { repository.restore(snapshot) }.onFailure(onError) }
    }
}
