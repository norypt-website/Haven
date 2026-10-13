package com.norypt.haven.ui.screens.notes

/** Pure helpers of the notes screens (no Android types, unit tested). */
object NoteLogic {
    private val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")

    /** A title lives on one line: line breaks (typed, pasted) become single spaces. Surrounding spaces stay. */
    fun singleLine(title: String): String = LINE_BREAKS.replace(title, " ")

    /** The title as stored: on one line and trimmed. */
    fun cleanTitle(title: String): String = singleLine(title).trim()

    /** The first non-blank line of [title] (older notes may hold several), else the first body line, else "Untitled". */
    fun displayTitle(title: String, body: String): String =
        firstLine(title).ifEmpty { body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Untitled" }

    private fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

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
