package com.example.data.repository

import com.example.data.database.dao.ClipboardItemDao
import com.example.data.database.entity.toDomainModel
import com.example.data.database.entity.toEntity
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ClipboardRepository(
    private val dao: ClipboardItemDao
) {
    val clipboardHistory: Flow<List<ClipboardItem>> = dao.observeAllItems().map { entities ->
        entities.map { it.toDomainModel() }
    }

    suspend fun insertClipboardItem(item: ClipboardItem) {
        dao.insertItem(item.toEntity())
    }

    suspend fun deleteClipboardItem(id: String) {
        dao.deleteItemById(id)
    }

    suspend fun deleteItemsByIds(ids: List<String>) {
        if (ids.isNotEmpty()) {
            dao.deleteItemsByIds(ids)
        }
    }

    suspend fun toggleFavorite(id: String, currentFavoriteState: Boolean) {
        dao.updateFavorite(id, !currentFavoriteState)
    }

    suspend fun togglePin(id: String, currentPinState: Boolean) {
        dao.updatePin(id, !currentPinState)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    suspend fun deleteExpiredItems(currentTime: Long = System.currentTimeMillis()): Int {
        return dao.deleteExpiredItems(currentTime)
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7L
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        fun calculateExpirationTime(
            createdAt: Long = System.currentTimeMillis(),
            retentionDays: Long = DEFAULT_RETENTION_DAYS
        ): Long {
            return createdAt + (retentionDays * MILLIS_PER_DAY)
        }
    }
}
