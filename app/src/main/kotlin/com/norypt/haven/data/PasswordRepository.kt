package com.norypt.haven.data

import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.passwords.FolderEntity
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Password keeper data access. Requires the password vault session to be open. */
class PasswordRepository(private val session: VaultSession) {
    private fun folders() = session.passwords().folders()
    private fun entries() = session.passwords().entries()

    fun observeFolders(): Flow<List<FolderEntity>> = folders().observeAll()
    fun observeEntries(): Flow<List<PasswordEntryEntity>> = entries().observeAll()
    fun observeByFolder(folderId: String?): Flow<List<PasswordEntryEntity>> = if (folderId == null) entries().observeUnfiled() else entries().observeByFolder(folderId)
    fun search(query: String): Flow<List<PasswordEntryEntity>> = entries().search(escapeLike(query))
    fun observeEntry(id: String): Flow<PasswordEntryEntity?> = entries().observe(id)
    fun observeCount(): Flow<Int> = entries().observeCount()
    suspend fun get(id: String): PasswordEntryEntity? = withContext(Dispatchers.IO) { entries().byId(id) }

    suspend fun createFolder(name: String): FolderEntity = withContext(Dispatchers.IO) {
        val f = FolderEntity(UUID.randomUUID().toString(), name.trim(), folders().nextPosition()); folders().upsert(f); f
    }
    suspend fun renameFolder(id: String, name: String) = withContext(Dispatchers.IO) { folders().all().firstOrNull { it.id == id }?.let { folders().upsert(it.copy(name = name.trim())) } }
    /** Entries in the folder become unfiled (FK SET NULL), never deleted. */
    suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) { folders().delete(id) }

    suspend fun create(title: String, website: String, username: String, password: String, notes: String, folderId: String?): PasswordEntryEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val e = PasswordEntryEntity(UUID.randomUUID().toString(), folderId, title.trim(), website.trim(), username, password, notes, now, now)
        entries().upsert(e); e
    }

    suspend fun update(entry: PasswordEntryEntity, title: String, website: String, username: String, password: String, notes: String, folderId: String?) = withContext(Dispatchers.IO) {
        entries().upsert(entry.copy(title = title.trim(), website = website.trim(), username = username, password = password, notes = notes, folderId = folderId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { entries().delete(id) }
    suspend fun deleteMany(ids: Collection<String>) = withContext(Dispatchers.IO) { entries().deleteAll(ids.toList()) }
    suspend fun deleteAll() = withContext(Dispatchers.IO) { entries().deleteEverything(); folders().deleteEverything() }

    private fun escapeLike(q: String): String = q.replace("%", "").replace("_", " ").trim()
}
