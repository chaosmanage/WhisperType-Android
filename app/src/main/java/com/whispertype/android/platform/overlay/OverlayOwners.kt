package com.whispertype.android.platform.overlay

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The union of the stable Compose owners that the persistent overlay requires
 * before its [androidx.compose.ui.platform.ComposeView] is attached (§2.2 /
 * Wispr FlowService parity). The owning runtime service implements this so the
 * host can install the same instance on the view tree for
 * [androidx.core.view.ViewTreeLifecycleOwner], the saved-state owner and the
 * view-model-store owner, avoiding a bare ComposeView.
 *
 * [startOwners] transitions the lifecycle to RESUMED once the window is attached
 * and [stopOwners] returns it to CREATED when detached, so content observes a
 * real lifecycle rather than a fabricated one.
 */
interface OverlayOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    fun startOwners()
    fun stopOwners()
}
