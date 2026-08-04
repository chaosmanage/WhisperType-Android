package com.whispertype.android.platform.overlay

import android.content.Context
import com.whispertype.android.core.contracts.OverlayController
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.TargetEligibility
import kotlinx.coroutines.flow.Flow

/**
 * Default [OverlayHostFactory]; public so the accessibility service can
 * construct a [PersistentOverlayHost] on its display-specific context.
 */
class DefaultOverlayHostFactory : OverlayHostFactory {
    override fun create(
        baseContext: Context,
        sessionState: Flow<DictationState>,
        eligibility: Flow<TargetEligibility>,
    ): OverlayController = PersistentOverlayHost(
        baseContext = baseContext,
        sessionState = sessionState,
        eligibility = eligibility,
    )
}
