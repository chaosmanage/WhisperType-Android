package com.whispertype.android.overlay

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A FrameLayout that provides [LifecycleOwner], [ViewModelStoreOwner], and
 * [SavedStateRegistryOwner] to its child ComposeView.  Required for Compose
 * overlays that live outside an Activity (e.g. accessibility-service
 * TYPE_ACCESSIBILITY_OVERLAY windows).
 *
 * The lifecycle advances to [Lifecycle.State.CREATED] when the view is added
 * to the window and to [Lifecycle.State.STARTED] when it becomes attached.
 */
class LifecycleOverlayView(context: Context) : FrameLayout(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        setViewTreeLifecycleOwner(this)
        setViewTreeViewModelStoreOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
    }

    fun setContent(content: @Composable () -> Unit) {
        addView(
            ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@LifecycleOverlayView)
                setViewTreeViewModelStoreOwner(this@LifecycleOverlayView)
                setViewTreeSavedStateRegistryOwner(this@LifecycleOverlayView)
                setContent(content)
            },
            LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Must be called when the overlay is removed to avoid leaking the registry. */
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
