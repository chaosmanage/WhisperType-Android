package com.whispertype.android.platform.accessibility

/**
 * Pure surrounding-text verification for a single-shot, cursor-aware insertion
 * (Phase 4, §4.5 step 6-7). Kept framework-free so the exactly-once / ambiguous
 * contract is host-testable without an input connection.
 *
 * Returns:
 *  - `true`  — the editor's surrounding text changed AND now contains [committed];
 *  - `false` — a definite no-change;
 *  - `null`  — could not be read / verified (treat as [Ambiguous], never retry).
 */
object InsertionVerifier {

    fun confirmed(
        before: String?,
        after: String?,
        committed: String,
    ): Boolean? {
        if (before == null || after == null) return null
        return after != before && after.contains(committed)
    }
}
