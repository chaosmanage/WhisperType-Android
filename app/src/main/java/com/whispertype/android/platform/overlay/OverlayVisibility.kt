package com.whispertype.android.platform.overlay

import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.OverlayUiState

/**
 * The visual surface the overlay should present for a given [OverlayUiState].
 * Mirrors PRD §17.2. Pure and framework-free so it runs on the JVM host test.
 */
enum class OverlayVisibility {
    Hidden,
    IdleBubble,
    Starting,
    Listening,
    Finalizing,
    Inserting,
    Success,
    CopyAvailable,
    Error,
}

/**
 * Pure §17.2 visibility mapping with no Android runtime dependencies.
 *
 * The Idle bubble shows only while the target is [com.whispertype.android.core.model.TargetEligibility.eligible];
 * any other Idle (ineligible/uncertain) configuration fails closed to [OverlayVisibility.Hidden].
 *
 * Every active session surface (Starting/Listening/Finalizing/Inserting/CopyAvailable/Error)
 * shows regardless of eligibility: a live session necessarily flips eligibility off via
 * `sessionActive`, so gating those on eligibility would incorrectly hide the recording panel.
 *
 * [DictationState.Success] and [DictationState.Cancelled] map to [OverlayVisibility.Hidden].
 * Success confirmation is surfaced by the accessibility service; immediately recreating the
 * bubble while the keyboard/target focus is being restored would fight the insertion flow.
 * This mapping is intentionally deterministic and host-testable.
 */
fun visibilityOf(ui: OverlayUiState): OverlayVisibility = when (ui.state) {
    is DictationState.Unavailable -> OverlayVisibility.Hidden
    is DictationState.Idle ->
        if (ui.eligibility.eligible) OverlayVisibility.IdleBubble else OverlayVisibility.Hidden
    is DictationState.Starting -> OverlayVisibility.Starting
    is DictationState.Listening -> OverlayVisibility.Listening
    is DictationState.Finalizing -> OverlayVisibility.Finalizing
    is DictationState.Inserting -> OverlayVisibility.Inserting
    is DictationState.Success -> OverlayVisibility.Hidden
    is DictationState.Cancelled -> OverlayVisibility.Hidden
    is DictationState.CopyAvailable -> OverlayVisibility.CopyAvailable
    is DictationState.Error -> OverlayVisibility.Error
}

/** Edge of the display the overlay window anchors to. Kept framework-free. */
enum class OverlayEdge { TopStart, TopEnd }

/**
 * Pure, host-testable overlay geometry expressed in dp. The WindowManager
 * adapter converts these into pixel LayoutParams (gravity + margins) using the
 * display-context density; it does not mix in IME/display guessed metrics.
 *
 * [minTouchDp] guarantees the bubble meets the §17.3 >= 48dp touch target.
 */
data class OverlayPlacement(
    val edge: OverlayEdge = OverlayEdge.TopStart,
    val marginDp: Float = 16f,
    val minTouchDp: Float = 48f,
    val surfaceSizeDp: Float = 72f,
) {
    val isValid: Boolean
        get() = marginDp >= 0f && minTouchDp >= 48f && surfaceSizeDp > 0f
}
