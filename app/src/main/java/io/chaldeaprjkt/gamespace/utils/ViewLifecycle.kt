/*
 * Copyright (C) 2026 GameSpace contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.utils

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/**
 * Lifecycle for views added straight to WindowManager (overlays have no
 * Activity/Fragment owner). RESUMED while attached, CREATED while detached.
 */
private class WindowViewLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun moveTo(state: Lifecycle.State) {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = state
    }

    fun destroy() {
        moveTo(Lifecycle.State.DESTROYED)
        store.clear()
    }
}

/**
 * Stand-in for SystemUI's `repeatWhenAttached`: installs view-tree owners on
 * this view and runs [block] in a scope tied to them. Use `repeatOnLifecycle`
 * inside [block] to react to attach/detach.
 */
fun View.repeatWhenAttached(block: suspend LifecycleOwner.(View) -> Unit): DisposableHandle {
    val view = this
    val owner = WindowViewLifecycleOwner()
    setViewTreeLifecycleOwner(owner)
    setViewTreeSavedStateRegistryOwner(owner)
    setViewTreeViewModelStoreOwner(owner)

    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = owner.moveTo(Lifecycle.State.RESUMED)
        override fun onViewDetachedFromWindow(v: View) = owner.moveTo(Lifecycle.State.CREATED)
    }
    addOnAttachStateChangeListener(listener)
    if (isAttachedToWindow) owner.moveTo(Lifecycle.State.RESUMED)

    owner.lifecycleScope.launch { owner.block(view) }

    return DisposableHandle {
        removeOnAttachStateChangeListener(listener)
        owner.destroy()
    }
}
