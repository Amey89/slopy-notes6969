package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.BackupDeletedItem
import com.example.data.model.BackupFolder
import com.example.data.model.BackupNote
import com.example.data.model.BackupPayload
import com.example.data.model.BackupTag
import com.example.data.model.BackupTask
import com.example.data.model.DeletedItemEntity
import com.example.data.model.NoteEntity
import com.example.data.model.SubTaskEntity
import com.example.data.model.TaskEntity
import com.example.data.repository.NotesAndTasksRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TombstoneSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: NotesAndTasksRepository
    private lateinit var context: Context

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NotesAndTasksRepository(
            noteDao = db.noteDao(),
            taskDao = db.taskDao(),
            folderDao = db.folderDao(),
            tagDao = db.tagDao(),
            database = db,
            deletedItemDao = db.deletedItemDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleted_note_and_task_do_not_resurrect_on_restore_or_sync() = runBlocking {
        val createdAtNote = 1_700_000_001_000L
        val createdAtTask = 1_700_000_002_000L

        val noteId = repository.insertNote(
            NoteEntity(
                title = "Secret Idea",
                content = "Do not come back",
                createdAt = createdAtNote,
                updatedAt = createdAtNote
            )
        )
        val keepNoteId = repository.insertNote(
            NoteEntity(
                title = "Keep Me",
                content = "Should stay",
                createdAt = 1_700_000_003_000L,
                updatedAt = 1_700_000_003_000L
            )
        )
        val taskId = repository.saveTaskWithSubtasks(
            TaskEntity(
                title = "Old Chore",
                description = "Delete me permanently",
                createdAt = createdAtTask,
                updatedAt = createdAtTask
            ),
            listOf(SubTaskEntity(parentTaskId = 0, title = "Step 1", orderIndex = 0))
        )

        // Snapshot backup BEFORE deletion (simulating stale Google Drive / old JSON backup)
        val staleBackupJson = repository.exportDatabaseToJson()
        assertTrue(staleBackupJson.contains("Secret Idea"))
        assertTrue(staleBackupJson.contains("Old Chore"))

        // User deletes the note and task locally
        repository.deleteNoteById(noteId)
        repository.deleteTaskById(taskId)

        assertFalse(db.noteDao().getAllNotesDirect().any { it.title == "Secret Idea" })
        assertFalse(db.taskDao().getAllTasksWithSubtasksDirect().any { it.task.title == "Old Chore" })
        assertTrue(db.noteDao().getAllNotesDirect().any { it.id == keepNoteId })

        // Verify tombstones were persisted
        val tombstones = db.deletedItemDao().getAllDeletedItemsDirect()
        assertTrue(tombstones.any { it.itemType == DeletedItemEntity.TYPE_NOTE && it.title == "Secret Idea" })
        assertTrue(tombstones.any { it.itemType == DeletedItemEntity.TYPE_TASK && it.title == "Old Chore" })

        // Now simulate Sync or Restore from stale backup JSON that still contains both deleted items
        val importSummary = repository.importDatabaseFromJsonDetailed(staleBackupJson).getOrThrow()
        assertTrue(importSummary.removedTaskIds.isEmpty())

        // Neither deleted item may resurrect
        val notesAfterRestore = db.noteDao().getAllNotesDirect()
        val tasksAfterRestore = db.taskDao().getAllTasksWithSubtasksDirect()
        assertFalse("Deleted note resurrected on restore!", notesAfterRestore.any { it.title == "Secret Idea" })
        assertFalse("Deleted task resurrected on restore!", tasksAfterRestore.any { it.task.title == "Old Chore" })
        assertTrue(notesAfterRestore.any { it.title == "Keep Me" })

        // Exported backup after sync omits deleted items and includes tombstones for PWA
        val exportedAfterSync = repository.exportDatabaseToJson()
        val payload = moshi.adapter(BackupPayload::class.java).fromJson(exportedAfterSync)
        assertNotNull(payload)
        assertFalse(payload!!.notes.any { it.title == "Secret Idea" })
        assertFalse(payload.tasks.any { it.title == "Old Chore" })
        assertTrue(payload.deletedItems.any { it.itemType == DeletedItemEntity.TYPE_NOTE && it.title == "Secret Idea" })
        assertTrue(payload.deletedItems.any { it.itemType == DeletedItemEntity.TYPE_TASK && it.title == "Old Chore" })
    }

    @Test
    fun remote_tombstones_from_pwa_delete_local_items_and_return_removed_task_ids() = runBlocking {
        val noteCreatedAt = 1_710_000_010_000L
        val taskCreatedAt = 1_710_000_020_000L
        val folderCreatedAt = 1_710_000_030_000L

        repository.insertNote(
            NoteEntity(
                title = "PWA Deleted Note",
                content = "Synced earlier",
                createdAt = noteCreatedAt,
                updatedAt = noteCreatedAt + 500L
            )
        )
        val localTaskId = repository.saveTaskWithSubtasks(
            TaskEntity(
                title = "PWA Deleted Task",
                description = "Has reminder",
                reminderEnabled = true,
                reminderTime = System.currentTimeMillis() + 3_600_000L,
                createdAt = taskCreatedAt,
                updatedAt = taskCreatedAt + 500L
            ),
            emptyList()
        )
        val localFolderId = db.folderDao().insertFolder(
            com.example.data.model.FolderEntity(
                name = "Temp Folder",
                folderType = "NOTE",
                createdAt = folderCreatedAt
            )
        )
        repository.createTag("TempTag", 1)

        assertTrue(localFolderId > 0)

        // Simulate a PWA Drive payload containing tombstones for all four items + one brand new remote note
        val deleteTimestamp = System.currentTimeMillis() + 10_000L
        val remotePayload = BackupPayload(
            exportVersion = 2,
            exportedAt = deleteTimestamp,
            notes = listOf(
                BackupNote(
                    title = "Fresh From PWA",
                    content = "Hello Android",
                    createdAt = 1_710_000_099_000L,
                    updatedAt = deleteTimestamp
                )
            ),
            tasks = emptyList(),
            folders = emptyList(),
            tags = emptyList(),
            deletedItems = listOf(
                BackupDeletedItem(
                    itemKey = DeletedItemEntity.noteKey("PWA Deleted Note", noteCreatedAt),
                    itemType = DeletedItemEntity.TYPE_NOTE,
                    title = "PWA Deleted Note",
                    createdAt = noteCreatedAt,
                    deletedAt = deleteTimestamp
                ),
                BackupDeletedItem(
                    itemKey = DeletedItemEntity.taskKey("PWA Deleted Task", taskCreatedAt),
                    itemType = DeletedItemEntity.TYPE_TASK,
                    title = "PWA Deleted Task",
                    createdAt = taskCreatedAt,
                    deletedAt = deleteTimestamp
                ),
                BackupDeletedItem(
                    itemKey = DeletedItemEntity.folderKey("Temp Folder", folderCreatedAt),
                    itemType = DeletedItemEntity.TYPE_FOLDER,
                    title = "Temp Folder",
                    createdAt = folderCreatedAt,
                    deletedAt = deleteTimestamp
                ),
                BackupDeletedItem(
                    itemKey = DeletedItemEntity.tagKey("TempTag"),
                    itemType = DeletedItemEntity.TYPE_TAG,
                    title = "TempTag",
                    createdAt = 0L,
                    deletedAt = deleteTimestamp
                )
            )
        )

        val remoteJson = moshi.adapter(BackupPayload::class.java).toJson(remotePayload)
        val summary = repository.importDatabaseFromJsonDetailed(remoteJson).getOrThrow()

        // Removed task ID must be returned so ViewModel can cancel AlarmManager reminders
        assertTrue(summary.removedTaskIds.contains(localTaskId))

        // Local items deleted by remote tombstones must be gone, while new remote note is imported
        val notes = db.noteDao().getAllNotesDirect()
        assertFalse(notes.any { it.title == "PWA Deleted Note" })
        assertTrue(notes.any { it.title == "Fresh From PWA" })

        val tasks = db.taskDao().getAllTasksWithSubtasksDirect()
        assertFalse(tasks.any { it.task.title == "PWA Deleted Task" })

        val folders = db.folderDao().getAllFoldersDirect()
        assertFalse(folders.any { it.name == "Temp Folder" })

        val tags = db.tagDao().getAllTagsDirect()
        assertFalse(tags.any { it.name.equals("TempTag", ignoreCase = true) })
    }

    @Test
    fun clock_skew_future_updatedAt_does_not_resurrect_deleted_item() = runBlocking {
        // Simulate an item created on another device whose clock is 1 hour ahead
        val futureTime = System.currentTimeMillis() + 3_600_000L
        val noteId = repository.insertNote(
            NoteEntity(
                title = "Future Clock Note",
                content = "From device with fast clock",
                createdAt = futureTime - 5_000L,
                updatedAt = futureTime
            )
        )

        val staleJson = repository.exportDatabaseToJson()
        repository.deleteNoteById(noteId)

        // Restore/sync with the stale payload that has updatedAt in the future
        repository.importDatabaseFromJsonDetailed(staleJson).getOrThrow()

        val remaining = db.noteDao().getAllNotesDirect()
        assertFalse("Future-clock note resurrected after deletion!", remaining.any { it.title == "Future Clock Note" })
    }

    @Test
    fun editing_note_and_task_preserves_createdAt_and_prevents_duplicates() = runBlocking {
        val origNoteCreatedAt = 1_705_000_001_000L
        val origTaskCreatedAt = 1_705_000_002_000L

        val noteId = repository.insertNote(
            NoteEntity(
                title = "Draft",
                content = "v1",
                createdAt = origNoteCreatedAt,
                updatedAt = origNoteCreatedAt
            )
        )
        val taskId = repository.saveTaskWithSubtasks(
            TaskEntity(
                title = "Original Task",
                description = "v1",
                isCompleted = true,
                tags = "Work",
                createdAt = origTaskCreatedAt,
                updatedAt = origTaskCreatedAt
            ),
            emptyList()
        )

        // Edit note via saveNoteWithFolders passing a default NoteEntity (which has a fresh createdAt = now)
        repository.saveNoteWithFolders(
            note = NoteEntity(
                id = noteId,
                title = "Renamed Draft",
                content = "v2"
            ),
            folderIds = emptyList()
        )

        // Edit task via saveTaskWithSubtasks passing a default TaskEntity (which has a fresh createdAt = now)
        repository.saveTaskWithSubtasks(
            task = TaskEntity(
                id = taskId,
                title = "Renamed Task",
                description = "v2"
            ),
            subtasks = emptyList()
        )

        val updatedNote = db.noteDao().getNoteByIdDirect(noteId)
        val updatedTask = repository.getTaskWithSubtasksDirect(taskId)?.task
        assertNotNull(updatedNote)
        assertNotNull(updatedTask)
        assertEquals(origNoteCreatedAt, updatedNote!!.createdAt)
        assertEquals(origTaskCreatedAt, updatedTask!!.createdAt)
        assertEquals("Renamed Draft", updatedNote.title)
        assertEquals("Renamed Task", updatedTask.title)
        assertTrue(updatedTask.isCompleted)
        assertEquals("Work", updatedTask.tags)
    }

    @Test
    fun recreating_deleted_tag_clears_tombstone_and_persists_across_sync() = runBlocking {
        repository.createTag("Focus", 2)
        repository.deleteTag("Focus")
        assertFalse(db.tagDao().getAllTagsDirect().any { it.name.equals("Focus", ignoreCase = true) })

        // Explicitly recreate the same tag
        repository.createTag("Focus", 3)
        val exported = repository.exportDatabaseToJson()
        repository.importDatabaseFromJsonDetailed(exported).getOrThrow()

        val tags = db.tagDao().getAllTagsDirect()
        assertTrue(tags.any { it.name.equals("Focus", ignoreCase = true) })
    }
}
