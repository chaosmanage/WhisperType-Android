package com.whispertype.android.accessibility

import android.graphics.Rect

/**
 * Immutable snapshot of the input target captured when a dictation session starts.
 *
 * Insertion is only allowed when the current live state still matches this token.
 * See Implementation Plan §9.2.
 */
data class TargetToken(
    val sessionId: String,
    val inputGeneration: Long,
    val packageName: String,
    val displayId: Int,
    val windowId: Int?,
    val fieldId: Int?,
    val inputType: Int,
    val initialSelectionStart: Int,
    val initialSelectionEnd: Int,
    val imeBounds: Rect,
)
