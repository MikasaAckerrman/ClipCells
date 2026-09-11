package com.clipcells.core

data class Cell(
    val id: Long,
    val name: String,
    val position: Int,
    val colorArgb: Long,
    val icon: String?,
    val intervalMillis: Long?,
    val messages: List<CellMessage>,
)

data class CellMessage(
    val id: Long,
    val text: String,
    val position: Int,
)

data class CopyItem(
    val sourceMessageId: Long,
    val text: String,
)

data class CopyPlan(
    val cellId: Long,
    val cellName: String,
    val intervalMillis: Long,
    val items: List<CopyItem>,
)

const val DEFAULT_COPY_INTERVAL_MILLIS = 1_000L
const val MIN_COPY_INTERVAL_MILLIS = 300L
const val MAX_COPY_INTERVAL_MILLIS = 3_000L
