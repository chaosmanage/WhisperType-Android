package com.whispertype.android.platform.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner

/**
 * A [FrameLayout] that implements all three stable Compose owners
 * ([LifecycleOwner], [SavedStateRegistryOwner], [ViewModelStoreOwner]) and
 * hosts the overlay's [androidx.compose.ui.platform.ComposeView] as its child.
 *
 * Compose's `WindowRecomposer` traverses the view tree upward from the
 * `ComposeView` to find the owners, so wrapping the ComposeView in this
 * container makes the owners discoverable **before** the composition starts —
 * fixing the `ViewTreeLifecycleOwner not found` crash (§2.2) without depending
 * on the `ViewTree*Owner.set()` API, which is not reliably on the compile
 * classpath across AndroidX lifecycle versions.
 *
 * The owners themselves are delegated to [owners] (the runtime service), so the
 * overlay observes a real lifecycle rather than a fabricated one.
 */
class OverlayComposeContainer @JvmOverloads constructor(
    context: Context,
    private val owners: OverlayOwners,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    override val lifecycle: Lifecycle get() = owners.lifecycle
    override val savedStateRegistry: SavedStateRegistry get() = owners.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = owners.viewModelStore
}

