package com.whispertype.android.platform.accessibility

import android.accessibilityservice.InputMethod
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.SurroundingText
import com.whispertype.android.core.contracts.TargetGateway
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.model.TargetSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * A live accessibility insertion handle for the target window: the focused
 * editable [node] plus the identity WhisperType needs to validate that the
 * captured target is still current before committing text.
 */
data class LiveTarget(
    val packageName: String,
    val windowId: Int,
    val generation: Long,
    val node: AccessibilityNodeInfo,
)

/**
 * Cursor-aware [TargetGateway] implementation over [EditorTracker] and the
 * accessibility IME surface (Phase 4, §4.5 / §2.5).
 *
 * Insertion no longer uses `ACTION_SET_TEXT` (which replaces the whole editor).
 * Instead it commits exactly once at the current cursor / selection through the
 * [InputMethod.AccessibilityInputConnection] exposed by
 * [android.accessibilityservice.InputMethod.getCurrentInputConnection], which
 * is the same `commitText()` semantics as a real IME, preserving surrounding
 * text and replacing only the selected range:
 *
 *  1. reacquire the live node and validate the captured target is still current,
 *  2. read surrounding text before committing,
 *  3. call `commitText(text, newCursorPosition)` once at the current selection,
 *  4. verify the surrounding-text change / selection advance,
 *  5. never blindly retry — an unverifiable result is [InsertionResult.Ambiguous]
 *     and routes to the Copy fallback.
 */
class AccessibilityTargetGateway(
    private val tracker: EditorTracker,
    private val liveTargetProvider: () -> LiveTarget?,
    private val inputConnectionProvider: () -> InputMethod.AccessibilityInputConnection?,
) : TargetGateway {

    override fun currentEligibility(): Flow<TargetEligibility> = tracker.eligibility

    override fun captureTarget(sessionId: SessionId): TargetSnapshot? {
        val eligibility = tracker.eligibility.value
        if (!eligibility.eligible) return null
        val focus = tracker.currentFocus ?: return null
        return TargetSnapshot(
            sessionId = sessionId,
            packageName = focus.packageName,
            displayId = focus.displayId,
            windowId = focus.windowId,
            editorIdentity = focus.editorIdentity ?: "",
            generation = focus.generation,
            inputTypeMask = focus.inputType,
            isSecure = focus.isSecure,
            isUncertain = focus.isUncertain,
            selectionStart = focus.selectionStart,
            selectionEnd = focus.selectionEnd,
            capturedAtMillis = System.currentTimeMillis(),
        )
    }

    override suspend fun insert(target: TargetSnapshot, text: String): InsertionResult {
        val ic = inputConnectionProvider()
        val live = liveTargetProvider()

        val connectionPresent = ic != null
        val targetCurrent = live != null &&
            live.packageName == target.packageName &&
            live.windowId == target.windowId &&
            live.generation == target.generation
        val targetSecureOrUncertain = target.isSecure || target.isUncertain

        var commitVerified: Boolean? = null
        if (ic != null && live != null && targetCurrent && !targetSecureOrUncertain) {
            // Single-shot, cursor-aware commit; never retried, even if ambiguous.
            commitVerified = commitAndVerify(ic, text)
        }

        return InsertionDecision.evaluate(
            connectionPresent = connectionPresent,
            targetCurrent = targetCurrent,
            targetSecureOrUncertain = targetSecureOrUncertain,
            commitAccepted = commitVerified,
        )
    }

    /**
     * Commits [text] at the current selection once and verifies the editor
     * reflected it. Returns `true` only on a confirmed change; `false` / `null`
     * on an unverifiable result (the caller maps that to Ambiguous, never a
     * retry). A `null` surrounding-text read is treated as ambiguous so a
     * closed / unstarted session is surfaced rather than mis-attributed.
     */
    private fun commitAndVerify(
        ic: InputMethod.AccessibilityInputConnection,
        text: String,
    ): Boolean? = try {
        val before = readSurrounding(ic) ?: return null
        ic.commitText(text, NEW_CURSOR_POSITION, null)
        val after = readSurrounding(ic) ?: return null
        InsertionVerifier.confirmed(
            before = before.getText().toString(),
            after = after.getText().toString(),
            committed = text,
        )
    } catch (_: Throwable) {
        null
    }

    private fun readSurrounding(ic: InputMethod.AccessibilityInputConnection): SurroundingText? = try {
        ic.getSurroundingText(SURROUNDING_BEFORE, SURROUNDING_AFTER, 0)
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val NEW_CURSOR_POSITION = 1
        const val SURROUNDING_BEFORE = 200
        const val SURROUNDING_AFTER = 400
    }
}
