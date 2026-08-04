package com.whispertype.android.platform.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
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
 * [TargetGateway] implementation over [EditorTracker]. Target capture is
 * explicit and immutable; insertion reacquires the live node, validates the
 * target is still current, then commits exactly once — never a retry of an
 * ambiguous commit and never synthetic keystrokes or clipboard paste.
 *
 * Note: the public Android SDK does not expose an `InputConnection` from an
 * accessibility node, so the accessibility-native `ACTION_SET_TEXT` (which
 * replaces the editor's content, like `InputConnection.commitText`) is used as
 * the single-shot commit. The typed, exactly-once result contract is preserved.
 */
class AccessibilityTargetGateway(
    private val tracker: EditorTracker,
    private val liveTargetProvider: () -> LiveTarget?,
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
        val live = liveTargetProvider()

        val connectionPresent = live != null
        val targetCurrent = live != null &&
            live.packageName == target.packageName &&
            live.windowId == target.windowId &&
            live.generation == target.generation
        val targetSecureOrUncertain = target.isSecure || target.isUncertain

        var commitAccepted: Boolean? = null
        if (live != null && targetCurrent && !targetSecureOrUncertain) {
            // Exactly-once commit via ACTION_SET_TEXT; never retried, even if it
            // returns ambiguous/false.
            commitAccepted = try {
                live.node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    },
                )
            } catch (_: Throwable) {
                false
            }
        }

        return InsertionDecision.evaluate(
            connectionPresent = connectionPresent,
            targetCurrent = targetCurrent,
            targetSecureOrUncertain = targetSecureOrUncertain,
            commitAccepted = commitAccepted,
        )
    }
}
