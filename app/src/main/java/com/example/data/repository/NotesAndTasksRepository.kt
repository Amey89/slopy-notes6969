package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.db.DeletedItemDao
import com.example.data.db.FolderDao
import com.example.data.db.NoteDao
import com.example.data.db.TagDao
import com.example.data.db.TaskDao
import com.example.data.model.BackupDeletedItem
import com.example.data.model.BackupFolder
import com.example.data.model.BackupNote
import com.example.data.model.BackupPayload
import com.example.data.model.BackupSubTask
import com.example.data.model.BackupTag
import com.example.data.model.BackupTask
import com.example.data.model.DeletedItemEntity
import com.example.data.model.FolderEntity
import com.example.data.model.NoteEntity
import com.example.data.model.SubTaskEntity
import com.example.data.model.TagEntity
import com.example.data.model.TaskEntity
import com.example.data.model.TaskWithSubtasks
import com.example.data.sync.MarkdownImportSummary
import com.example.data.sync.MarkdownZipBundleManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JsonImportSummary(
    val importedCount: Int,
    val removedTaskIds: List<Long> = emptyList()
)

class NotesAndTasksRepository(
    private val noteDao: NoteDao,
    private val taskDao: TaskDao,
    private val folderDao: FolderDao,
    private val tagDao: TagDao,
    private val database: AppDatabase? = null,
    private val deletedItemDao: DeletedItemDao? = database?.deletedItemDao()
) {
    val allTags: Flow<List<TagEntity>> = tagDao.getAllTags()

    val allNotes: Flow<List<NoteEntity>> = noteDao.getAllNotes()
    val starredNotes: Flow<List<NoteEntity>> = noteDao.getStarredNotes()

    val allTasks: Flow<List<TaskWithSubtasks>> = taskDao.getAllTasksWithSubtasks()
    val starredTasks: Flow<List<TaskWithSubtasks>> = taskDao.getStarredTasksWithSubtasks()

    val allFolders: Flow<List<FolderEntity>> = folderDao.getAllFolders()
    val rootFolders: Flow<List<FolderEntity>> = folderDao.getRootFolders()
    val noteFolders: Flow<List<FolderEntity>> = folderDao.getFoldersByType("NOTE")
    val rootNoteFolders: Flow<List<FolderEntity>> = folderDao.getRootFoldersByType("NOTE")
    val taskFolders: Flow<List<FolderEntity>> = folderDao.getFoldersByType("TASK")
    val rootTaskFolders: Flow<List<FolderEntity>> = folderDao.getRootFoldersByType("TASK")

    fun getSubfolders(parentId: Long): Flow<List<FolderEntity>> = folderDao.getSubfolders(parentId)

    fun searchNotes(query: String): Flow<List<NoteEntity>> = noteDao.searchNotes(query)
    fun searchTasks(query: String): Flow<List<TaskWithSubtasks>> = taskDao.searchTasks(query)

    fun getNotesByFolder(folderId: Long): Flow<List<NoteEntity>> = noteDao.getNotesByFolder(folderId)
    fun getTasksByFolder(folderId: Long): Flow<List<TaskWithSubtasks>> = taskDao.getTasksWithSubtasksByFolder(folderId)

    fun getNoteById(id: Long): Flow<NoteEntity?> = noteDao.getNoteById(id)
    fun getTaskWithSubtasksById(id: Long): Flow<TaskWithSubtasks?> = taskDao.getTaskWithSubtasksById(id)

    fun getFolderIdsForNoteFlow(noteId: Long): Flow<List<Long>> = folderDao.getFolderIdsForNoteFlow(noteId)
    fun getFoldersForNote(noteId: Long): Flow<List<FolderEntity>> = folderDao.getFoldersForNote(noteId)
    suspend fun getFolderIdsForNote(noteId: Long): List<Long> = withContext(Dispatchers.IO) {
        folderDao.getFolderIdsForNote(noteId)
    }

    fun getFolderIdsForTaskFlow(taskId: Long): Flow<List<Long>> = folderDao.getFolderIdsForTaskFlow(taskId)
    fun getFoldersForTask(taskId: Long): Flow<List<FolderEntity>> = folderDao.getFoldersForTask(taskId)
    suspend fun getFolderIdsForTask(taskId: Long): List<Long> = withContext(Dispatchers.IO) {
        folderDao.getFolderIdsForTask(taskId)
    }

    suspend fun createFolder(
        name: String,
        folderType: String = "NOTE",
        parentFolderId: Long? = null,
        colorIndex: Int = 0
    ): Long = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        // If user explicitly recreates a previously deleted folder with the same name, clear its tombstone
        if (trimmed.isNotBlank()) {
            deletedItemDao?.deleteByTypeAndLowerTitle(DeletedItemEntity.TYPE_FOLDER, trimmed.lowercase())
        }
        folderDao.insertFolder(
            FolderEntity(
                name = trimmed,
                folderType = folderType,
                parentFolderId = parentFolderId,
                colorIndex = colorIndex
            )
        )
    }

    suspend fun updateFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        folderDao.updateFolder(folder)
    }

    suspend fun deleteFolder(folderId: Long) = withContext(Dispatchers.IO) {
        if (!FolderEntity.isPermanentFolderId(folderId)) {
            val allFoldersSnapshot = folderDao.getAllFoldersDirect()
            val foldersToDelete = allFoldersSnapshot.filter {
                (it.id == folderId || it.parentFolderId == folderId) && !FolderEntity.isPermanentFolderId(it.id)
            }
            val tombstones = foldersToDelete.map { folder ->
                DeletedItemEntity(
                    itemKey = DeletedItemEntity.folderKey(folder.name, folder.createdAt),
                    itemType = DeletedItemEntity.TYPE_FOLDER,
                    title = folder.name.trim(),
                    createdAt = folder.createdAt,
                    deletedAt = DeletedItemEntity.effectiveDeletedAt(folder.createdAt, folder.createdAt)
                )
            }
            if (tombstones.isNotEmpty()) {
                deletedItemDao?.insertDeletedItems(tombstones)
            }
        }
        folderDao.deleteNoteCrossRefsForFolder(folderId)
        folderDao.deleteTaskCrossRefsForFolder(folderId)
        folderDao.detachNotesFromFolder(folderId)
        folderDao.detachTasksFromFolder(folderId)
        folderDao.deleteSubfolders(folderId)
        folderDao.deleteFolder(folderId)
    }

    suspend fun createTag(name: String, colorIndex: Int = 0): Unit = withContext(Dispatchers.IO) {
        val clean = name.trim().removePrefix("#")
        if (clean.isNotBlank()) {
            deletedItemDao?.deleteByKey(DeletedItemEntity.tagKey(clean))
            tagDao.insertTag(TagEntity(name = clean, colorIndex = colorIndex))
        }
    }

    suspend fun deleteTag(name: String): Unit = withContext(Dispatchers.IO) {
        val clean = name.trim().removePrefix("#")
        if (clean.isNotBlank()) {
            val existingTag = tagDao.getAllTagsDirect().find { it.name.equals(clean, ignoreCase = true) }
            val createdAt = existingTag?.createdAt ?: 0L
            deletedItemDao?.insertDeletedItem(
                DeletedItemEntity(
                    itemKey = DeletedItemEntity.tagKey(clean),
                    itemType = DeletedItemEntity.TYPE_TAG,
                    title = clean,
                    createdAt = createdAt,
                    deletedAt = DeletedItemEntity.effectiveDeletedAt(createdAt, createdAt)
                )
            )
        }
        tagDao.deleteTag(name)
    }

    suspend fun ensureDefaultTags(): Unit = withContext(Dispatchers.IO) {
        if (tagDao.getTagCount() == 0) {
            val defaults = listOf(
                TagEntity("Work", colorIndex = 0),
                TagEntity("Personal", colorIndex = 1),
                TagEntity("Ideas", colorIndex = 2),
                TagEntity("Urgent", colorIndex = 3),
                TagEntity("Study", colorIndex = 4),
                TagEntity("Project", colorIndex = 5)
            )
            defaults.forEach { tagDao.insertTag(it) }
        }
    }

    suspend fun setNoteFolders(noteId: Long, folderIds: List<Long>) = withContext(Dispatchers.IO) {
        folderDao.deleteNoteFolderCrossRefsForNote(noteId)
        folderIds.distinct().forEach { fId ->
            folderDao.insertNoteFolderCrossRef(com.example.data.model.NoteFolderCrossRef(noteId, fId))
        }
        val firstFolderId = folderIds.firstOrNull()
        noteDao.setNoteFolder(noteId, firstFolderId)
    }

    suspend fun setTaskFolders(taskId: Long, folderIds: List<Long>) = withContext(Dispatchers.IO) {
        folderDao.deleteTaskFolderCrossRefsForTask(taskId)
        folderIds.distinct().forEach { fId ->
            folderDao.insertTaskFolderCrossRef(com.example.data.model.TaskFolderCrossRef(taskId, fId))
        }
        val firstFolderId = folderIds.firstOrNull()
        taskDao.setTaskFolder(taskId, firstFolderId)
    }

    suspend fun saveNoteWithFolders(note: NoteEntity, folderIds: List<Long>): Long = withContext(Dispatchers.IO) {
        val firstFolder = folderIds.firstOrNull()
        val noteToSave = note.copy(folderId = firstFolder)
        val noteId = if (noteToSave.id == 0L) {
            noteDao.insert(noteToSave)
        } else {
            // Preserve original createdAt so edits keep stable cross-device identity
            val existing = noteDao.getNoteByIdDirect(noteToSave.id)
            val preservedCreatedAt = existing?.createdAt ?: noteToSave.createdAt
            noteDao.update(
                noteToSave.copy(
                    createdAt = preservedCreatedAt,
                    updatedAt = System.currentTimeMillis()
                )
            )
            noteToSave.id
        }
        folderDao.deleteNoteFolderCrossRefsForNote(noteId)
        folderIds.distinct().forEach { fId ->
            folderDao.insertNoteFolderCrossRef(com.example.data.model.NoteFolderCrossRef(noteId, fId))
        }
        noteId
    }

    suspend fun setNoteFolder(noteId: Long, folderId: Long?) = withContext(Dispatchers.IO) {
        setNoteFolders(noteId, if (folderId != null) listOf(folderId) else emptyList())
    }

    suspend fun setTaskFolder(taskId: Long, folderId: Long?) = withContext(Dispatchers.IO) {
        setTaskFolders(taskId, if (folderId != null) listOf(folderId) else emptyList())
    }

    suspend fun insertNote(note: NoteEntity): Long = withContext(Dispatchers.IO) {
        noteDao.insert(note)
    }

    suspend fun updateNote(note: NoteEntity) = withContext(Dispatchers.IO) {
        val existing = noteDao.getNoteByIdDirect(note.id)
        noteDao.update(
            note.copy(
                createdAt = existing?.createdAt ?: note.createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun recordNoteTombstone(note: NoteEntity) {
        deletedItemDao?.insertDeletedItem(
            DeletedItemEntity(
                itemKey = DeletedItemEntity.noteKey(note.title, note.createdAt),
                itemType = DeletedItemEntity.TYPE_NOTE,
                title = note.title.trim(),
                createdAt = note.createdAt,
                deletedAt = DeletedItemEntity.effectiveDeletedAt(note.updatedAt, note.createdAt)
            )
        )
    }

    private suspend fun recordTaskTombstone(task: TaskEntity) {
        deletedItemDao?.insertDeletedItem(
            DeletedItemEntity(
                itemKey = DeletedItemEntity.taskKey(task.title, task.createdAt),
                itemType = DeletedItemEntity.TYPE_TASK,
                title = task.title.trim(),
                createdAt = task.createdAt,
                deletedAt = DeletedItemEntity.effectiveDeletedAt(task.updatedAt, task.createdAt)
            )
        )
    }

    suspend fun deleteNote(note: NoteEntity) = withContext(Dispatchers.IO) {
        recordNoteTombstone(note)
        folderDao.deleteNoteFolderCrossRefsForNote(note.id)
        noteDao.delete(note)
    }

    suspend fun deleteNoteById(id: Long) = withContext(Dispatchers.IO) {
        val existing = noteDao.getNoteByIdDirect(id)
        if (existing != null) {
            recordNoteTombstone(existing)
        }
        folderDao.deleteNoteFolderCrossRefsForNote(id)
        noteDao.deleteById(id)
    }

    suspend fun toggleNoteStar(id: Long, currentStarred: Boolean) = withContext(Dispatchers.IO) {
        noteDao.setStarred(id, !currentStarred)
    }

    // Task and subtask operations
    suspend fun insertTask(task: TaskEntity, subtasks: List<String> = emptyList()): Long = withContext(Dispatchers.IO) {
        val taskId = taskDao.insertTask(task)
        if (subtasks.isNotEmpty()) {
            val entities = subtasks.mapIndexed { index, title ->
                SubTaskEntity(
                    parentTaskId = taskId,
                    title = title,
                    isCompleted = false,
                    orderIndex = index
                )
            }
            taskDao.insertSubTasks(entities)
        }
        taskId
    }

    suspend fun updateTask(task: TaskEntity) = withContext(Dispatchers.IO) {
        val existing = taskDao.getTaskByIdDirect(task.id)
        taskDao.updateTask(
            task.copy(
                createdAt = existing?.createdAt ?: task.createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveTaskWithSubtasks(
        task: TaskEntity,
        subtasks: List<SubTaskEntity>
    ): Long = withContext(Dispatchers.IO) {
        val taskId = if (task.id == 0L) {
            taskDao.insertTask(task)
        } else {
            val existing = taskDao.getTaskByIdDirect(task.id)
            taskDao.updateTask(
                task.copy(
                    createdAt = existing?.createdAt ?: task.createdAt,
                    isCompleted = existing?.isCompleted ?: task.isCompleted,
                    tags = if (task.tags.isBlank() && existing != null) existing.tags else task.tags,
                    updatedAt = System.currentTimeMillis()
                )
            )
            task.id
        }

        // Replace subtasks
        taskDao.deleteSubTasksByTaskId(taskId)
        if (subtasks.isNotEmpty()) {
            val updatedSubtasks = subtasks.mapIndexed { idx, st ->
                st.copy(id = 0, parentTaskId = taskId, orderIndex = idx)
            }
            taskDao.insertSubTasks(updatedSubtasks)
        }
        taskId
    }

    suspend fun saveTaskWithFolders(
        task: TaskEntity,
        subtasks: List<SubTaskEntity>,
        folderIds: List<Long>
    ): Long = withContext(Dispatchers.IO) {
        val firstFolder = folderIds.firstOrNull()
        val taskToSave = task.copy(folderId = firstFolder)
        val taskId = saveTaskWithSubtasks(taskToSave, subtasks)
        folderDao.deleteTaskFolderCrossRefsForTask(taskId)
        folderIds.distinct().forEach { fId ->
            folderDao.insertTaskFolderCrossRef(com.example.data.model.TaskFolderCrossRef(taskId, fId))
        }
        taskId
    }

    suspend fun deleteTask(task: TaskEntity) = withContext(Dispatchers.IO) {
        recordTaskTombstone(task)
        folderDao.deleteTaskFolderCrossRefsForTask(task.id)
        taskDao.deleteSubTasksByTaskId(task.id)
        taskDao.deleteTask(task)
    }

    suspend fun deleteTaskById(id: Long) = withContext(Dispatchers.IO) {
        val existing = taskDao.getTaskByIdDirect(id)
        if (existing != null) {
            recordTaskTombstone(existing)
        }
        folderDao.deleteTaskFolderCrossRefsForTask(id)
        taskDao.deleteSubTasksByTaskId(id)
        taskDao.deleteTaskById(id)
    }

    suspend fun toggleTaskCompleted(id: Long, isCompleted: Boolean) = withContext(Dispatchers.IO) {
        taskDao.setTaskCompleted(id, isCompleted)
    }

    suspend fun toggleTaskStar(id: Long, currentStarred: Boolean) = withContext(Dispatchers.IO) {
        taskDao.setTaskStarred(id, !currentStarred)
    }

    suspend fun updateTaskReminder(id: Long, reminderTime: Long?, enabled: Boolean) = withContext(Dispatchers.IO) {
        taskDao.updateReminder(id, reminderTime, enabled)
    }

    suspend fun addSubTask(parentTaskId: Long, title: String): Long = withContext(Dispatchers.IO) {
        taskDao.insertSubTask(
            SubTaskEntity(
                parentTaskId = parentTaskId,
                title = title,
                isCompleted = false
            )
        )
    }

    suspend fun toggleSubTaskCompleted(subtaskId: Long, isCompleted: Boolean) = withContext(Dispatchers.IO) {
        taskDao.setSubTaskCompleted(subtaskId, isCompleted)
    }

    suspend fun deleteSubTask(subtaskId: Long) = withContext(Dispatchers.IO) {
        taskDao.deleteSubTaskById(subtaskId)
    }

    // Direct access
    suspend fun getNoteDirect(id: Long): NoteEntity? = withContext(Dispatchers.IO) {
        noteDao.getNoteByIdDirect(id)
    }

    suspend fun getTaskDirect(id: Long): TaskEntity? = withContext(Dispatchers.IO) {
        taskDao.getTaskByIdDirect(id)
    }

    suspend fun getTaskWithSubtasksDirect(id: Long): TaskWithSubtasks? = withContext(Dispatchers.IO) {
        taskDao.getTaskWithSubtasksByIdDirect(id)
    }

    suspend fun getActivePendingReminders(now: Long): List<TaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getActivePendingReminders(now)
    }

    // Local Data Backup & Export / Import
    private val moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    suspend fun exportDatabaseToJson(prettyPrint: Boolean = false): String = withContext(Dispatchers.IO) {
        // Explicitly include all permanent system folders and Shared Links platform subfolders in JSON backup
        val permanentFolders = FolderEntity.AllPermanentFolders.map { pf ->
            BackupFolder(
                id = pf.id,
                name = pf.name,
                folderType = pf.folderType,
                parentFolderId = pf.parentFolderId,
                colorIndex = pf.colorIndex,
                createdAt = pf.createdAt
            )
        }
        val dbFolders = folderDao.getAllFoldersDirect()
            .filter { !FolderEntity.isPermanentFolderId(it.id) }
            .map {
                BackupFolder(
                    id = it.id,
                    name = it.name,
                    folderType = it.folderType,
                    parentFolderId = it.parentFolderId,
                    colorIndex = it.colorIndex,
                    createdAt = it.createdAt
                )
            }
        val folders = permanentFolders + dbFolders

        val tags = tagDao.getAllTagsDirect().map {
            BackupTag(
                name = it.name,
                colorIndex = it.colorIndex,
                createdAt = it.createdAt
            )
        }

        val notes = noteDao.getAllNotesDirect().map {
            // Strip any accidental inline base64 data blobs so sync payload stays lightweight (KB, never GB)
            val lightweightImageUris = if (it.imageUris.isBlank()) ""
            else it.imageUris.split(",")
                .map { uri -> uri.trim() }
                .filter { uri -> uri.isNotBlank() && !uri.startsWith("data:", ignoreCase = true) && uri.length < 2048 }
                .joinToString(",")

            BackupNote(
                title = it.title,
                content = it.content,
                tags = it.tags,
                imageUris = lightweightImageUris,
                isStarred = it.isStarred,
                colorIndex = it.colorIndex,
                folderId = it.folderId,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt
            )
        }
        val tasks = taskDao.getAllTasksWithSubtasksDirect().map { tws ->
            val effectiveFolderId = when {
                tws.task.folderId == FolderEntity.COMPLETED_TASKS_FOLDER_ID -> FolderEntity.COMPLETED_TASKS_FOLDER_ID
                tws.task.isCompleted && tws.task.folderId == null -> FolderEntity.COMPLETED_TASKS_FOLDER_ID
                else -> tws.task.folderId
            }
            BackupTask(
                title = tws.task.title,
                description = tws.task.description,
                isCompleted = tws.task.isCompleted,
                isStarred = tws.task.isStarred,
                dueDate = tws.task.dueDate,
                reminderTime = tws.task.reminderTime,
                reminderEnabled = tws.task.reminderEnabled,
                priority = tws.task.priority,
                tags = tws.task.tags,
                folderId = effectiveFolderId,
                createdAt = tws.task.createdAt,
                updatedAt = tws.task.updatedAt,
                subtasks = tws.subtasks.map {
                    BackupSubTask(
                        title = it.title,
                        isCompleted = it.isCompleted,
                        orderIndex = it.orderIndex
                    )
                }
            )
        }

        val cutoff = System.currentTimeMillis() - DeletedItemEntity.RETENTION_MS
        deletedItemDao?.pruneOlderThan(cutoff)
        val deletedItems = (deletedItemDao?.getAllDeletedItemsDirect() ?: emptyList())
            .take(2000)
            .map {
                BackupDeletedItem(
                    itemKey = it.itemKey,
                    itemType = it.itemType,
                    title = it.title,
                    createdAt = it.createdAt,
                    deletedAt = it.deletedAt
                )
            }

        val payload = BackupPayload(
            notes = notes,
            tasks = tasks,
            folders = folders,
            tags = tags,
            deletedItems = deletedItems
        )

        val adapter = moshi.adapter(BackupPayload::class.java)
        if (prettyPrint) adapter.indent("  ").toJson(payload) else adapter.toJson(payload)
    }

    private fun normalizeTombstone(raw: BackupDeletedItem): DeletedItemEntity? {
        val type = raw.itemType.trim().uppercase()
        if (type.isBlank()) return null
        val cleanTitle = raw.title.trim()
        val key = raw.itemKey.trim().ifBlank {
            when (type) {
                DeletedItemEntity.TYPE_NOTE -> DeletedItemEntity.noteKey(cleanTitle, raw.createdAt)
                DeletedItemEntity.TYPE_TASK -> DeletedItemEntity.taskKey(cleanTitle, raw.createdAt)
                DeletedItemEntity.TYPE_FOLDER -> DeletedItemEntity.folderKey(cleanTitle, raw.createdAt)
                DeletedItemEntity.TYPE_TAG -> DeletedItemEntity.tagKey(cleanTitle)
                else -> "$type|${cleanTitle.lowercase()}|${raw.createdAt}"
            }
        }
        return DeletedItemEntity(
            itemKey = key,
            itemType = type,
            title = cleanTitle,
            createdAt = raw.createdAt,
            deletedAt = raw.deletedAt
        )
    }

    private fun findMatchingTombstones(
        tombstonesByType: Map<String, List<DeletedItemEntity>>,
        itemType: String,
        title: String,
        createdAt: Long,
        sameCreatedAtCount: Int
    ): List<DeletedItemEntity> {
        val candidates = tombstonesByType[itemType] ?: return emptyList()
        val normalizedTitle = title.trim().lowercase()
        val canonicalKey = when (itemType) {
            DeletedItemEntity.TYPE_NOTE -> DeletedItemEntity.noteKey(title, createdAt)
            DeletedItemEntity.TYPE_TASK -> DeletedItemEntity.taskKey(title, createdAt)
            DeletedItemEntity.TYPE_FOLDER -> DeletedItemEntity.folderKey(title, createdAt)
            DeletedItemEntity.TYPE_TAG -> DeletedItemEntity.tagKey(title)
            else -> "$itemType|$normalizedTitle|$createdAt"
        }

        return candidates.filter { t ->
            if (t.itemKey.equals(canonicalKey, ignoreCase = true)) {
                true
            } else {
                when (itemType) {
                    DeletedItemEntity.TYPE_TAG -> {
                        t.title.trim().removePrefix("#").equals(title.trim().removePrefix("#"), ignoreCase = true)
                    }
                    DeletedItemEntity.TYPE_FOLDER -> {
                        val sameName = t.title.trim().equals(title.trim(), ignoreCase = true)
                        sameName && (t.createdAt == 0L || t.createdAt == createdAt || t.deletedAt >= createdAt)
                    }
                    else -> {
                        val sameCreated = t.createdAt > 0L && createdAt > 0L && t.createdAt == createdAt
                        val sameTitle = t.title.trim().equals(title.trim(), ignoreCase = true)
                        (sameCreated && sameTitle) ||
                            (sameCreated && sameCreatedAtCount <= 1) ||
                            (t.createdAt == 0L && sameTitle)
                    }
                }
            }
        }
    }

    suspend fun importDatabaseFromJson(jsonString: String): Result<Int> {
        return importDatabaseFromJsonDetailed(jsonString).map { it.importedCount }
    }

    suspend fun importDatabaseFromJsonDetailed(jsonString: String): Result<JsonImportSummary> = withContext(Dispatchers.IO) {
        try {
            val adapter = moshi.adapter(BackupPayload::class.java)
            val payload = adapter.fromJson(jsonString) ?: return@withContext Result.failure(Exception("Invalid JSON format"))

            val runBatchImport: suspend () -> JsonImportSummary = {
                var importedCount = 0
                val removedTaskIds = mutableListOf<Long>()
                val now = System.currentTimeMillis()
                val cutoff = now - DeletedItemEntity.RETENTION_MS

                // 1. Merge local and remote deletion tombstones (latest deletedAt wins per key)
                val mergedTombstones = mutableMapOf<String, DeletedItemEntity>()
                (deletedItemDao?.getAllDeletedItemsDirect() ?: emptyList()).forEach { localT ->
                    if (localT.deletedAt >= cutoff) {
                        mergedTombstones[localT.itemKey] = localT
                    }
                }
                payload.deletedItems.forEach { remoteRaw ->
                    val normalized = normalizeTombstone(remoteRaw)
                    if (normalized != null && normalized.deletedAt >= cutoff) {
                        val existing = mergedTombstones[normalized.itemKey]
                        if (existing == null || normalized.deletedAt > existing.deletedAt) {
                            mergedTombstones[normalized.itemKey] = normalized
                        }
                    }
                }
                if (mergedTombstones.isNotEmpty()) {
                    deletedItemDao?.insertDeletedItems(mergedTombstones.values.toList())
                }
                val tombstonesByType = mergedTombstones.values.groupBy { it.itemType }

                // 2. Apply merged tombstones to EXISTING local items (so items deleted on PWA/other device are removed here!)
                val initialLocalNotes = noteDao.getAllNotesDirect()
                val localNoteCreatedCounts = initialLocalNotes.groupingBy { it.createdAt }.eachCount()
                for (localNote in initialLocalNotes) {
                    val matches = findMatchingTombstones(
                        tombstonesByType = tombstonesByType,
                        itemType = DeletedItemEntity.TYPE_NOTE,
                        title = localNote.title,
                        createdAt = localNote.createdAt,
                        sameCreatedAtCount = localNoteCreatedCounts[localNote.createdAt] ?: 1
                    )
                    val maxDel = matches.maxOfOrNull { it.deletedAt }
                    val itemLatest = maxOf(localNote.updatedAt, localNote.createdAt)
                    if (maxDel != null && maxDel >= itemLatest) {
                        folderDao.deleteNoteFolderCrossRefsForNote(localNote.id)
                        noteDao.deleteById(localNote.id)
                    }
                }

                val initialLocalTasks = taskDao.getAllTasksWithSubtasksDirect()
                val localTaskCreatedCounts = initialLocalTasks.groupingBy { it.task.createdAt }.eachCount()
                for (localTws in initialLocalTasks) {
                    val t = localTws.task
                    val matches = findMatchingTombstones(
                        tombstonesByType = tombstonesByType,
                        itemType = DeletedItemEntity.TYPE_TASK,
                        title = t.title,
                        createdAt = t.createdAt,
                        sameCreatedAtCount = localTaskCreatedCounts[t.createdAt] ?: 1
                    )
                    val maxDel = matches.maxOfOrNull { it.deletedAt }
                    val itemLatest = maxOf(t.updatedAt, t.createdAt)
                    if (maxDel != null && maxDel >= itemLatest) {
                        removedTaskIds.add(t.id)
                        folderDao.deleteTaskFolderCrossRefsForTask(t.id)
                        taskDao.deleteSubTasksByTaskId(t.id)
                        taskDao.deleteTaskById(t.id)
                    }
                }

                val initialLocalFolders = folderDao.getAllFoldersDirect()
                for (localFolder in initialLocalFolders) {
                    if (FolderEntity.isPermanentFolderId(localFolder.id)) continue
                    val matches = findMatchingTombstones(
                        tombstonesByType = tombstonesByType,
                        itemType = DeletedItemEntity.TYPE_FOLDER,
                        title = localFolder.name,
                        createdAt = localFolder.createdAt,
                        sameCreatedAtCount = 1
                    )
                    val maxDel = matches.maxOfOrNull { it.deletedAt }
                    if (maxDel != null && maxDel >= localFolder.createdAt) {
                        folderDao.deleteNoteCrossRefsForFolder(localFolder.id)
                        folderDao.deleteTaskCrossRefsForFolder(localFolder.id)
                        folderDao.detachNotesFromFolder(localFolder.id)
                        folderDao.detachTasksFromFolder(localFolder.id)
                        folderDao.deleteFolder(localFolder.id)
                    }
                }

                val initialLocalTags = tagDao.getAllTagsDirect()
                for (localTag in initialLocalTags) {
                    val matches = findMatchingTombstones(
                        tombstonesByType = tombstonesByType,
                        itemType = DeletedItemEntity.TYPE_TAG,
                        title = localTag.name,
                        createdAt = localTag.createdAt,
                        sameCreatedAtCount = 1
                    )
                    val maxDel = matches.maxOfOrNull { it.deletedAt }
                    if (maxDel != null && maxDel >= localTag.createdAt) {
                        tagDao.deleteTag(localTag.name)
                    }
                }

                // 3. Import remote Folders (respecting tombstones)
                val folderIdMap = mutableMapOf<Long, Long>()

                // Pre-seed permanent folder IDs so notes/tasks mapped to them point to the system IDs
                FolderEntity.AllPermanentFolders.forEach { pf ->
                    folderIdMap[pf.id] = pf.id
                }

                val existingFolders = folderDao.getAllFoldersDirect()
                val existingFolderByKey = existingFolders.associateBy {
                    "${it.name.trim().lowercase()}_${it.parentFolderId ?: 0L}"
                }.toMutableMap()

                for (f in payload.folders) {
                    if (f.id == FolderEntity.COMPLETED_TASKS_FOLDER_ID || f.name.equals("Completed Tasks", ignoreCase = true) || f.name.equals("Permanent Archive", ignoreCase = true)) {
                        folderIdMap[f.id] = FolderEntity.COMPLETED_TASKS_FOLDER_ID
                        continue
                    }
                    if (f.id == FolderEntity.SHARED_LINKS_FOLDER_ID || f.name.equals("Shared Links", ignoreCase = true)) {
                        folderIdMap[f.id] = FolderEntity.SHARED_LINKS_FOLDER_ID
                        continue
                    }
                    if (FolderEntity.isSharedLinksSubfolderId(f.id)) {
                        folderIdMap[f.id] = f.id
                        continue
                    }
                    val matchedPermanentSub = if (f.parentFolderId == FolderEntity.SHARED_LINKS_FOLDER_ID) {
                        FolderEntity.matchPermanentSharedSubfolderByName(f.name)
                    } else null
                    if (matchedPermanentSub != null) {
                        folderIdMap[f.id] = matchedPermanentSub.id
                        continue
                    }

                    val folderTombstones = findMatchingTombstones(
                        tombstonesByType = tombstonesByType,
                        itemType = DeletedItemEntity.TYPE_FOLDER,
                        title = f.name,
                        createdAt = f.createdAt,
                        sameCreatedAtCount = 1
                    )
                    val maxFolderDel = folderTombstones.maxOfOrNull { it.deletedAt }
                    if (maxFolderDel != null && maxFolderDel >= f.createdAt) {
                        continue
                    } else if (folderTombstones.isNotEmpty()) {
                        folderTombstones.forEach { deletedItemDao?.deleteByKey(it.itemKey) }
                    }

                    val mappedParent = f.parentFolderId?.let { folderIdMap[it] }
                    val key = "${f.name.trim().lowercase()}_${mappedParent ?: 0L}"
                    val existing = existingFolderByKey[key]
                    if (existing != null) {
                        folderIdMap[f.id] = existing.id
                    } else {
                        val folderEntity = FolderEntity(
                            name = f.name,
                            folderType = f.folderType ?: "NOTE",
                            parentFolderId = mappedParent,
                            colorIndex = f.colorIndex,
                            createdAt = f.createdAt
                        )
                        val newId = folderDao.insertFolder(folderEntity)
                        folderIdMap[f.id] = newId
                        existingFolderByKey[key] = folderEntity.copy(id = newId)
                        importedCount++
                    }
                }

                // 4. Import remote Tags (respecting tombstones)
                for (tag in payload.tags) {
                    val clean = tag.name.trim().removePrefix("#")
                    if (clean.isBlank()) continue
                    val tagTombstones = findMatchingTombstones(
                        tombstonesByType = tombstonesByType,
                        itemType = DeletedItemEntity.TYPE_TAG,
                        title = clean,
                        createdAt = tag.createdAt,
                        sameCreatedAtCount = 1
                    )
                    val maxTagDel = tagTombstones.maxOfOrNull { it.deletedAt }
                    if (maxTagDel != null && maxTagDel >= tag.createdAt) {
                        continue
                    } else if (tagTombstones.isNotEmpty()) {
                        tagTombstones.forEach { deletedItemDao?.deleteByKey(it.itemKey) }
                    }
                    tagDao.insertTag(TagEntity(name = clean, colorIndex = tag.colorIndex, createdAt = tag.createdAt))
                }

                // 5. Import remote Notes (respecting tombstones — deleted notes NEVER come back!)
                val survivingLocalNotes = noteDao.getAllNotesDirect()
                val existingNotesByKey = survivingLocalNotes.associateBy {
                    "${it.title.trim().lowercase()}_${it.createdAt}"
                }.toMutableMap()
                val existingNotesByCreatedAt = survivingLocalNotes.groupBy { it.createdAt }
                val remoteNoteCreatedCounts = payload.notes.groupingBy { it.createdAt }.eachCount()

                for (note in payload.notes) {
                    val noteTombstones = findMatchingTombstones(
                        tombstonesByType = tombstonesByType,
                        itemType = DeletedItemEntity.TYPE_NOTE,
                        title = note.title,
                        createdAt = note.createdAt,
                        sameCreatedAtCount = remoteNoteCreatedCounts[note.createdAt] ?: 1
                    )
                    val maxNoteDel = noteTombstones.maxOfOrNull { it.deletedAt }
                    val noteLatest = maxOf(note.updatedAt, note.createdAt)
                    if (maxNoteDel != null && maxNoteDel >= noteLatest) {
                        // Tombstone wins — do NOT resurrect deleted note
                        continue
                    } else if (noteTombstones.isNotEmpty()) {
                        noteTombstones.forEach { deletedItemDao?.deleteByKey(it.itemKey) }
                    }

                    val mappedFolderId = when {
                        FolderEntity.isPermanentFolderId(note.folderId) -> note.folderId
                        note.folderId != null -> folderIdMap[note.folderId]
                        else -> null
                    }
                    val key = "${note.title.trim().lowercase()}_${note.createdAt}"
                    val existingNote = existingNotesByKey[key] ?: run {
                        if (note.createdAt > 0L) {
                            val sameCreated = existingNotesByCreatedAt[note.createdAt]
                            if (sameCreated != null && sameCreated.size == 1 && (remoteNoteCreatedCounts[note.createdAt] ?: 1) == 1) {
                                sameCreated.first()
                            } else null
                        } else null
                    }

                    val noteId = if (existingNote != null) {
                        if (note.updatedAt > existingNote.updatedAt) {
                            val updatedEntity = existingNote.copy(
                                title = note.title,
                                content = note.content,
                                tags = note.tags,
                                imageUris = note.imageUris,
                                isStarred = note.isStarred,
                                colorIndex = note.colorIndex,
                                folderId = mappedFolderId,
                                updatedAt = note.updatedAt
                            )
                            noteDao.update(updatedEntity)
                            existingNotesByKey[key] = updatedEntity
                            importedCount++
                        }
                        existingNote.id
                    } else {
                        val insertedEntity = NoteEntity(
                            title = note.title,
                            content = note.content,
                            tags = note.tags,
                            imageUris = note.imageUris,
                            isStarred = note.isStarred,
                            colorIndex = note.colorIndex,
                            folderId = mappedFolderId,
                            createdAt = note.createdAt,
                            updatedAt = note.updatedAt
                        )
                        val newId = noteDao.insert(insertedEntity)
                        existingNotesByKey[key] = insertedEntity.copy(id = newId)
                        importedCount++
                        newId
                    }
                    if (mappedFolderId != null) {
                        folderDao.insertNoteFolderCrossRef(com.example.data.model.NoteFolderCrossRef(noteId, mappedFolderId))
                        if (FolderEntity.isSharedLinksSubfolderId(mappedFolderId)) {
                            folderDao.insertNoteFolderCrossRef(com.example.data.model.NoteFolderCrossRef(noteId, FolderEntity.SHARED_LINKS_FOLDER_ID))
                        }
                    }
                }

                // 6. Import remote Tasks (respecting tombstones — deleted tasks NEVER come back!)
                val survivingLocalTasks = taskDao.getAllTasksWithSubtasksDirect()
                val existingTasksByKey = survivingLocalTasks.associateBy {
                    "${it.task.title.trim().lowercase()}_${it.task.createdAt}"
                }.toMutableMap()
                val existingTasksByCreatedAt = survivingLocalTasks.groupBy { it.task.createdAt }
                val remoteTaskCreatedCounts = payload.tasks.groupingBy { it.createdAt }.eachCount()

                for (task in payload.tasks) {
                    val taskTombstones = findMatchingTombstones(
                        tombstonesByType = tombstonesByType,
                        itemType = DeletedItemEntity.TYPE_TASK,
                        title = task.title,
                        createdAt = task.createdAt,
                        sameCreatedAtCount = remoteTaskCreatedCounts[task.createdAt] ?: 1
                    )
                    val maxTaskDel = taskTombstones.maxOfOrNull { it.deletedAt }
                    val taskLatest = maxOf(task.updatedAt, task.createdAt)
                    if (maxTaskDel != null && maxTaskDel >= taskLatest) {
                        // Tombstone wins — do NOT resurrect deleted task
                        continue
                    } else if (taskTombstones.isNotEmpty()) {
                        taskTombstones.forEach { deletedItemDao?.deleteByKey(it.itemKey) }
                    }

                    val mappedFolderId = when {
                        task.folderId == FolderEntity.COMPLETED_TASKS_FOLDER_ID -> FolderEntity.COMPLETED_TASKS_FOLDER_ID
                        task.folderId == FolderEntity.SHARED_LINKS_FOLDER_ID -> FolderEntity.SHARED_LINKS_FOLDER_ID
                        task.isCompleted && (task.folderId == null || task.folderId == FolderEntity.COMPLETED_TASKS_FOLDER_ID) -> FolderEntity.COMPLETED_TASKS_FOLDER_ID
                        task.folderId != null -> folderIdMap[task.folderId]
                        else -> null
                    }
                    val key = "${task.title.trim().lowercase()}_${task.createdAt}"
                    val existingTask = existingTasksByKey[key] ?: run {
                        if (task.createdAt > 0L) {
                            val sameCreated = existingTasksByCreatedAt[task.createdAt]
                            if (sameCreated != null && sameCreated.size == 1 && (remoteTaskCreatedCounts[task.createdAt] ?: 1) == 1) {
                                sameCreated.first()
                            } else null
                        } else null
                    }

                    val taskId = if (existingTask != null) {
                        if (task.updatedAt > existingTask.task.updatedAt) {
                            val updatedTask = existingTask.task.copy(
                                title = task.title,
                                description = task.description,
                                isCompleted = task.isCompleted,
                                isStarred = task.isStarred,
                                dueDate = task.dueDate,
                                reminderTime = task.reminderTime,
                                reminderEnabled = task.reminderEnabled,
                                priority = task.priority,
                                tags = task.tags,
                                folderId = mappedFolderId,
                                updatedAt = task.updatedAt
                            )
                            taskDao.updateTask(updatedTask)
                            taskDao.deleteSubTasksByTaskId(existingTask.task.id)
                            val subEntities = if (task.subtasks.isNotEmpty()) {
                                val list = task.subtasks.map {
                                    SubTaskEntity(
                                        parentTaskId = existingTask.task.id,
                                        title = it.title,
                                        isCompleted = it.isCompleted,
                                        orderIndex = it.orderIndex
                                    )
                                }
                                taskDao.insertSubTasks(list)
                                list
                            } else emptyList()
                            existingTasksByKey[key] = TaskWithSubtasks(updatedTask, subEntities)
                            importedCount++
                        }
                        existingTask.task.id
                    } else {
                        val newTaskEntity = TaskEntity(
                            title = task.title,
                            description = task.description,
                            isCompleted = task.isCompleted,
                            isStarred = task.isStarred,
                            dueDate = task.dueDate,
                            reminderTime = task.reminderTime,
                            reminderEnabled = task.reminderEnabled,
                            priority = task.priority,
                            tags = task.tags,
                            folderId = mappedFolderId,
                            createdAt = task.createdAt,
                            updatedAt = task.updatedAt
                        )
                        val newTaskId = taskDao.insertTask(newTaskEntity)
                        val subEntities = if (task.subtasks.isNotEmpty()) {
                            val list = task.subtasks.map {
                                SubTaskEntity(
                                    parentTaskId = newTaskId,
                                    title = it.title,
                                    isCompleted = it.isCompleted,
                                    orderIndex = it.orderIndex
                                )
                            }
                            taskDao.insertSubTasks(list)
                            list
                        } else emptyList()
                        existingTasksByKey[key] = TaskWithSubtasks(newTaskEntity.copy(id = newTaskId), subEntities)
                        importedCount++
                        newTaskId
                    }
                    if (mappedFolderId != null) {
                        folderDao.insertTaskFolderCrossRef(com.example.data.model.TaskFolderCrossRef(taskId, mappedFolderId))
                    }
                }
                JsonImportSummary(importedCount = importedCount, removedTaskIds = removedTaskIds)
            }

            // Execute entire restore/merge inside a single atomic SQLite transaction (30x-50x faster than per-row fsyncs!)
            val summary = if (database != null) {
                database.withTransaction { runBatchImport() }
            } else {
                runBatchImport()
            }

            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportMarkdownZipBundle(context: Context): File = withContext(Dispatchers.IO) {
        val dbFolders = folderDao.getAllFoldersDirect()
        val allFolders = (FolderEntity.AllPermanentFolders + dbFolders).distinctBy { it.id }
        val notes = noteDao.getAllNotesDirect()
        val tasks = taskDao.getAllTasksWithSubtasksDirect()
        MarkdownZipBundleManager.exportZipBundle(
            context = context,
            folders = allFolders,
            notes = notes,
            tasks = tasks
        )
    }

    suspend fun importMarkdownZipBundle(inputStream: java.io.InputStream): Result<MarkdownImportSummary> = withContext(Dispatchers.IO) {
        MarkdownZipBundleManager.importZipBundle(
            inputStream = inputStream,
            folderDao = folderDao,
            noteDao = noteDao,
            taskDao = taskDao
        )
    }

    suspend fun exportToMarkdownBundle(context: Context): File {
        return exportMarkdownZipBundle(context)
    }

    // Custom Data Deletion options (record tombstones so bulk-deleted items stay deleted across sync/restore)
    suspend fun deleteNotesCustom(
        deleteAllNotes: Boolean,
        deleteUnstarredOnly: Boolean
    ) = withContext(Dispatchers.IO) {
        val currentNotes = noteDao.getAllNotesDirect()
        if (deleteAllNotes) {
            val tombstones = currentNotes.map { note ->
                DeletedItemEntity(
                    itemKey = DeletedItemEntity.noteKey(note.title, note.createdAt),
                    itemType = DeletedItemEntity.TYPE_NOTE,
                    title = note.title.trim(),
                    createdAt = note.createdAt,
                    deletedAt = DeletedItemEntity.effectiveDeletedAt(note.updatedAt, note.createdAt)
                )
            }
            if (tombstones.isNotEmpty()) deletedItemDao?.insertDeletedItems(tombstones)
            folderDao.deleteAllNoteCrossRefs()
            noteDao.deleteAllNotes()
        } else if (deleteUnstarredOnly) {
            val unstarred = currentNotes.filter { !it.isStarred }
            val tombstones = unstarred.map { note ->
                DeletedItemEntity(
                    itemKey = DeletedItemEntity.noteKey(note.title, note.createdAt),
                    itemType = DeletedItemEntity.TYPE_NOTE,
                    title = note.title.trim(),
                    createdAt = note.createdAt,
                    deletedAt = DeletedItemEntity.effectiveDeletedAt(note.updatedAt, note.createdAt)
                )
            }
            if (tombstones.isNotEmpty()) deletedItemDao?.insertDeletedItems(tombstones)
            unstarred.forEach { folderDao.deleteNoteFolderCrossRefsForNote(it.id) }
            noteDao.deleteUnstarredNotes()
        }
    }

    suspend fun deleteTasksCustom(
        deleteAllTasks: Boolean,
        deleteCompletedOnly: Boolean
    ) = withContext(Dispatchers.IO) {
        val currentTasks = taskDao.getAllTasksWithSubtasksDirect().map { it.task }
        if (deleteAllTasks) {
            val tombstones = currentTasks.map { task ->
                DeletedItemEntity(
                    itemKey = DeletedItemEntity.taskKey(task.title, task.createdAt),
                    itemType = DeletedItemEntity.TYPE_TASK,
                    title = task.title.trim(),
                    createdAt = task.createdAt,
                    deletedAt = DeletedItemEntity.effectiveDeletedAt(task.updatedAt, task.createdAt)
                )
            }
            if (tombstones.isNotEmpty()) deletedItemDao?.insertDeletedItems(tombstones)
            folderDao.deleteAllTaskCrossRefs()
            taskDao.deleteAllSubtasks()
            taskDao.deleteAllTasks()
        } else if (deleteCompletedOnly) {
            val completed = currentTasks.filter { it.isCompleted }
            val tombstones = completed.map { task ->
                DeletedItemEntity(
                    itemKey = DeletedItemEntity.taskKey(task.title, task.createdAt),
                    itemType = DeletedItemEntity.TYPE_TASK,
                    title = task.title.trim(),
                    createdAt = task.createdAt,
                    deletedAt = DeletedItemEntity.effectiveDeletedAt(task.updatedAt, task.createdAt)
                )
            }
            if (tombstones.isNotEmpty()) deletedItemDao?.insertDeletedItems(tombstones)
            completed.forEach {
                folderDao.deleteTaskFolderCrossRefsForTask(it.id)
                taskDao.deleteSubTasksByTaskId(it.id)
            }
            taskDao.deleteCompletedTasks()
        }
    }

    suspend fun deleteCustomFolders() = withContext(Dispatchers.IO) {
        val customFolders = folderDao.getAllFoldersDirect().filter { !FolderEntity.isPermanentFolderId(it.id) }
        val tombstones = customFolders.map { folder ->
            DeletedItemEntity(
                itemKey = DeletedItemEntity.folderKey(folder.name, folder.createdAt),
                itemType = DeletedItemEntity.TYPE_FOLDER,
                title = folder.name.trim(),
                createdAt = folder.createdAt,
                deletedAt = DeletedItemEntity.effectiveDeletedAt(folder.createdAt, folder.createdAt)
            )
        }
        if (tombstones.isNotEmpty()) deletedItemDao?.insertDeletedItems(tombstones)
        folderDao.deleteAllCustomFolders()
    }

    suspend fun deleteCustomTags() = withContext(Dispatchers.IO) {
        val tags = tagDao.getAllTagsDirect()
        val tombstones = tags.map { tag ->
            DeletedItemEntity(
                itemKey = DeletedItemEntity.tagKey(tag.name),
                itemType = DeletedItemEntity.TYPE_TAG,
                title = tag.name.trim(),
                createdAt = tag.createdAt,
                deletedAt = DeletedItemEntity.effectiveDeletedAt(tag.createdAt, tag.createdAt)
            )
        }
        if (tombstones.isNotEmpty()) deletedItemDao?.insertDeletedItems(tombstones)
        tagDao.deleteAllTags()
    }

    suspend fun clearEntireAppData() = withContext(Dispatchers.IO) {
        folderDao.deleteAllNoteCrossRefs()
        folderDao.deleteAllTaskCrossRefs()
        taskDao.deleteAllSubtasks()
        taskDao.deleteAllTasks()
        noteDao.deleteAllNotes()
        folderDao.deleteAllFolders()
        tagDao.deleteAllTags()
        deletedItemDao?.deleteAllDeletedItems()
        // Re-seed default permanent folders/tags
        ensureDefaultTags()
    }
}
