package com.whispertype.android.platform.overlay

import android.content.Context
import com.whispertype.android.core.contracts.OverlayController
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.TargetEligibility
import kotlinx.coroutines.flow.Flow

/**
 * Creates the persistent overlay host. Owned by the platform-overlay
 * workstream; consumed by the accessibility service, which attaches it once
 * per service lifetime on its display-specific context.
 */
interface OverlayHostFactory {
    fun create(
        baseContext: Context,
        sessionState: Flow<DictationState>,
        eligibility: Flow<TargetEligibility>,
    ): OverlayController
}