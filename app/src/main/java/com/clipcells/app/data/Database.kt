package com.clipcells.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cells", indices = [Index(value = ["position"], unique = true)])
data class CellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
    val colorArgb: Long,
    val icon: String?,
    val intervalMillis: Long?,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = CellEntity::class,
        parentColumns = ["id"],
        childColumns = ["cellId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("cellId"), Index(value = ["cellId", "position"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cellId: Long,
    val text: String,
    val position: Int,
)

data class CellWithMessages(
    @Embedded val cell: CellEntity,
    @Relation(parentColumn = "id", entityColumn = "cellId")
    val messages: List<MessageEntity>,
) {
    val orderedMessages: List<MessageEntity> get() = messages.sortedBy(MessageEntity::position)
}

@Entity(tableName = "copy_queue")
data class CopyQueueEntity(
    @PrimaryKey val id: Int = SINGLE_QUEUE_ID,
    val title: String,
    val intervalMillis: Long,
    val nextIndex: Int,
    val revision: Long,
)

@Entity(
    tableName = "copy_queue_items",
    primaryKeys = ["queueId", "position"],
    foreignKeys = [ForeignKey(
        entity = CopyQueueEntity::class,
        parentColumns = ["id"],
        childColumns = ["queueId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("queueId")],
)
data class CopyQueueItemEntity(
    val queueId: Int = SINGLE_QUEUE_ID,
    val position: Int,
    val sourceMessageId: Long,
    val text: String,
)

@Dao
interface CellDao {
    @Transaction
    @Query("SELECT * FROM cells ORDER BY position")
    fun observeAll(): Flow<List<CellWithMessages>>

    @Transaction
    @Query("SELECT * FROM cells WHERE id = :id")
    suspend fun get(id: Long): CellWithMessages?

    @Insert suspend fun insertCell(cell: CellEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMessages(items: List<MessageEntity>)
    @Query("UPDATE cells SET name=:name, colorArgb=:color, icon=:icon, intervalMillis=:interval WHERE id=:id")
    suspend fun updateCell(id: Long, name: String, color: Long, icon: String?, interval: Long?)
    @Query("DELETE FROM messages WHERE cellId=:cellId") suspend fun deleteMessages(cellId: Long)
    @Query("DELETE FROM cells WHERE id IN (:ids)") suspend fun deleteCells(ids: Set<Long>)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM cells") suspend fun nextPosition(): Int
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM copy_queue WHERE id = 1") suspend fun getQueue(): CopyQueueEntity?
    @Query("SELECT * FROM copy_queue WHERE id = 1") fun observeQueue(): Flow<CopyQueueEntity?>
    @Query("SELECT * FROM copy_queue_items WHERE queueId = 1 ORDER BY position")
    suspend fun getItems(): List<CopyQueueItemEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putQueue(queue: CopyQueueEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putItems(items: List<CopyQueueItemEntity>)
    @Query("DELETE FROM copy_queue_items WHERE queueId = 1") suspend fun deleteItems()
    @Query("DELETE FROM copy_queue WHERE id = 1") suspend fun deleteQueue()
    @Query("UPDATE copy_queue SET nextIndex=:nextIndex WHERE id=1 AND revision=:revision")
    suspend fun advance(revision: Long, nextIndex: Int): Int
    @Query("DELETE FROM copy_queue WHERE id=1 AND revision=:revision") suspend fun finish(revision: Long): Int
}

@Database(
    entities = [CellEntity::class, MessageEntity::class, CopyQueueEntity::class, CopyQueueItemEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ClipCellsDatabase : RoomDatabase() {
    abstract fun cellDao(): CellDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile private var instance: ClipCellsDatabase? = null

        fun get(context: Context): ClipCellsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ClipCellsDatabase::class.java,
                "clipcells.db",
            ).build().also { instance = it }
        }
    }
}

const val SINGLE_QUEUE_ID = 1
