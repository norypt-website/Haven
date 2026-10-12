package com.norypt.haven.ui.screens.notes

/** Pure helpers of the notes screens (no Android types, unit tested). */
object NoteLogic {
    fun displayTitle(title: String, body: String): String =
        title.trim().ifEmpty { body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Untitled" }

    fun preview(title: String, body: String): String {
        if (title.isNotBlank()) return body.trim()
        val lines = body.lines().dropWhile { it.isBlank() }
        return lines.drop(1).joinToString("\n").trim()
    }

    /** A note that was never stored and has no text is dropped; an existing note is kept until deleted. */
    fun shouldStore(isNew: Boolean, title: String, body: String): Boolean = !isNew || title.isNotBlank() || body.isNotBlank()

    /** Words in the whole note: runs of non-space characters in the title and the body. */
    fun wordCount(title: String, body: String): Int = WORD.findAll(title).count() + WORD.findAll(body).count()

    private val WORD = Regex("\\S+")

    fun escapeLike(q: String): String = q.replace("%", "").replace("_", " ").trim()
}
