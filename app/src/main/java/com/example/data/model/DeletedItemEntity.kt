package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "deleted_items")
data class DeletedItemEntity(
    @PrimaryKey
    val itemKey: String,
    val itemType: String, // "NOTE", "TASK", "FOLDER", "TAG"
    val title: String = "",
    val createdAt: Long = 0L,
    val deletedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_NOTE = "NOTE"
        const val TYPE_TASK = "TASK"
        const val TYPE_FOLDER = "FOLDER"
        const val TYPE_TAG = "TAG"

        // Keep tombstones for 180 days so cross-device sync stays consistent even across infrequent syncs
        const val RETENTION_MS = 180L * 24L * 60L * 60L * 1000L

        fun noteKey(title: String, createdAt: Long): String {
            return "$TYPE_NOTE|${title.trim().lowercase()}|$createdAt"
        }

        fun taskKey(title: String, createdAt: Long): String {
            return "$TYPE_TASK|${title.trim().lowercase()}|$createdAt"
        }

        fun folderKey(name: String, createdAt: Long): String {
            return "$TYPE_FOLDER|${name.trim().lowercase()}|$createdAt"
        }

        fun tagKey(name: String): String {
            return "$TYPE_TAG|${name.trim().removePrefix("#").lowercase()}"
        }

        /**
         * Guarantees deletedAt is strictly greater than the deleted item's own timestamps,
         * preventing resurrection even when two devices have minor clock skew.
         */
        fun effectiveDeletedAt(itemUpdatedAt: Long, itemCreatedAt: Long): Long {
            val itemLatest = maxOf(itemUpdatedAt, itemCreatedAt)
            return maxOf(System.currentTimeMillis(), itemLatest + 1L)
        }
    }
}
