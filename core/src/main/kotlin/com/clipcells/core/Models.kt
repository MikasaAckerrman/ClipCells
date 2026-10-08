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


