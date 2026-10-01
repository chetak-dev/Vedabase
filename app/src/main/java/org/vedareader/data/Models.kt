package org.vedareader.data

import java.util.Locale

data class LibraryInfo(
    val id: String,
    val title: String,
    val languageTag: String,
    val fileName: String,
    val size: Long,
    val records: Long,
    val copyright: String,
) {
    val languageName: String
        get() = Locale.forLanguageTag(languageTag).getDisplayName(Locale.getDefault())
}

data class ContentNode(
    val record: Int,
    val parent: Int,
    val title: String,
    val end: Int,
    val children: Int,
    val level: Int,
    val code: String = "",
) {
    val displayTitle: String get() = ContentTitles.display(title)
    fun location(libraryId: String) = ReadingLocation(libraryId, displayTitle, record, end, record)
}

data class ReadingLocation(
    val libraryId: String,
    val title: String,
    val start: Int,
    val end: Int,
    val position: Int,
    val scrollY: Int = 0,
)

data class Paragraph(val record: Int, val text: String, val style: String, val level: String)
data class VersePreview(val node: ContentNode, val translation: String?)
data class ChapterOverview(val chapter: ContentNode, val verses: List<VersePreview>)
data class ReadingPage(val paragraphs: List<Paragraph>, val previous: Int?, val next: Int?)
enum class ReadingUnitKind { VERSE, CHAPTER, SECTION }
data class ReadingSection(
    val node: ContentNode,
    val kind: ReadingUnitKind,
    val parentTitle: String,
    val paragraphs: List<Paragraph>,
    val previous: ContentNode? = null,
    val next: ContentNode? = null,
)
enum class ReadingFailure { LOAD, CHOOSE_SECTION, SECTION_TOO_LARGE }
class ReadingException(val reason: ReadingFailure) : Exception(reason.name)
data class SearchHit(val location: ReadingLocation, val snippet: String)
data class SearchResults(val hits: List<SearchHit> = emptyList(), val total: Int = 0)
data class Definition(val word: String, val dictionary: String, val meaning: String)
data class ContentObject(val mimeType: String, val bytes: ByteArray)
data class SavedPassage(val id: String, val location: ReadingLocation, val note: String)

/** Reader paper themes in picker order; persisted by name, so LIGHT/SEPIA/DARK keep their meaning. */
enum class ReaderPaper { LIGHT, SEPIA, GREEN, GRAY, DARK }
data class ReaderPreferences(
    /** CSS baseline in px; the reader zooms natively from this, it is not the user's chosen size. */
    val fontSize: Int = 20,
    val paper: ReaderPaper = ReaderPaper.LIGHT,
    /** Percentage of the original line heights; independent of reader/system text size. */
    val lineSpacing: Int = 100,
) {
    companion object {
        /** Default user text size; matches the chapter list's 16sp translation text. */
        const val DEFAULT_TEXT_SIZE = 16
    }
}

enum class ImportStage { COPYING, CHECKING, FINISHING }
data class ImportProgress(val stage: ImportStage, val percent: Int? = null, val copiedBytes: Long = 0)
enum class ImportFailure { LANGUAGE, SPACE, INVALID_DATABASE, INCOMPLETE, OPEN, STORAGE }
class LibraryImportException(val reason: ImportFailure) : Exception(reason.name)