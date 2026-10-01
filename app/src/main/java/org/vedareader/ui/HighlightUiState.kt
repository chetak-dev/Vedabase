package org.vedareader.ui

import org.vedareader.data.Highlight
import org.vedareader.data.HighlightColor

enum class HighlightMessage { SAVED, REMOVED, FAILED }
data class HighlightNotice(val id: Long, val message: HighlightMessage, val undoId: String? = null)

/** A committed annotation. The reader acknowledges it without opening a tray or editor. */
data class HighlightCreation(val token: Long, val id: String, val withNote: Boolean)

/** Presentation snapshot of the local highlight store; the owner keeps the store itself. */
data class HighlightUiState(
    val items: List<Highlight> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val notice: HighlightNotice? = null,
    val defaultColor: HighlightColor = HighlightColor.YELLOW,
    val created: HighlightCreation? = null,
)
