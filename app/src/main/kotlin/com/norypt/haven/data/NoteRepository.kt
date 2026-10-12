package com.norypt.haven.data

import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.content.NoteEntity
import com.norypt.haven.ui.screens.notes.NoteLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Notes data access. Requires the content vault to be open. */
class NoteRepository(private val session: VaultSession) {
    private fun dao() = session.content().notes()

    fun observeAll(): Flow<List<NoteEntity>> = dao().observeAll()
    fun search(q: String): Flow<List<NoteEntity>> = dao().search(NoteLogic.escapeLike(q))
    fun observe(id: String): Flow<NoteEntity?> = dao().observe(id)
    fun observeRecent(limit: Int): Flow<List<NoteEntity>> = dao().observeRecent(limit)
    fun observeCount(): Flow<Int> = dao().observeCount()
    suspend fun get(id: String): NoteEntity? = withContext(Dispatchers.IO) { dao().byId(id) }

    /**
     * Creates or updates a note. Returns its id, or null when nothing was stored: a new note
     * without text, or the vault closed in the meantime (an autosave racing a lock).
     */
    suspend fun save(id: String?, title: String, body: String): String? = withContext(Dispatchers.IO) {
        if (!NoteLogic.shouldStore(isNew = id == null, title, body)) return@withContext null
        val db = session.contentOrNull() ?: return@withContext null
        runCatching {
            val now = System.currentTimeMillis()
            val existing = id?.let { db.notes().byId(it) }
            val note = existing?.copy(title = title, body = body, updatedAt = now)
                ?: NoteEntity(id ?: UUID.randomUUID().toString(), title, body, now, now)
            if (existing != null && existing.title == title && existing.body == body) return@runCatching note.id
            db.notes().upsert(note); note.id
        }.getOrNull()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao().delete(id) }
}
