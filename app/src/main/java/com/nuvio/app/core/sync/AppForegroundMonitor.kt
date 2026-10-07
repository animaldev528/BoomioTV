package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TV compat layer — **not** part of the overlay port.
 *
 * The overlay observes foreground/background to decide when a browse, a probe or the tunnel
 * should stand down, and it reads that from `com.nuvio.app.core.sync.AppForegroundMonitor`. The
 * TV has no equivalent anywhere (no `ProcessLifecycleOwner`, no `AppVisibility`), so this file
 * re-declares the one member the overlay uses.
 *
 * ⚠️ **`androidx.lifecycle:lifecycle-process` is NOT on the TV classpath** — measured, it is
 * declared in no module and `ProcessLifecycleOwner` appears in no `.kt` source, only in the
 * committed `baseline-prof.txt`. So mobile's `actual` (which drives this from
 * `ProcessLifecycleOwner`) cannot be transplanted; this stand-in is a plain state holder instead.
 *
 * ⚠️ **Nothing drives [AppForegroundMonitor.notify] yet, and that is deliberate.** An inert port
 * adds no call sites — see the port's step 4. Until a lifecycle hook calls `notify`, `events()`
 * emits `Foreground` and stays there, which is the correct inert reading: nothing collects it
 * while `BOOMIO_OVERLAY_ADDR` is blank. The driver belongs to the commit that calls the overlay's
 * `initialize()`, and the natural hooks there are `MainActivity.onStart`/`onStop`.
 */
internal enum class AppVisibility {
    Foreground,
    Background,
}

/**
 * Reports whether the app is in the foreground, as a flow the overlay can collect.
 *
 * Emits the *current* value on subscribe, which is what mobile's implementation does too
 * (`trySend` of the current state before `awaitClose`), so a collector that starts late still
 * learns the state it started in rather than waiting for the next transition.
 */
internal object AppForegroundMonitor {

    /**
     * Seeded to [AppVisibility.Foreground] rather than `Background`: an app that has just started
     * is in the foreground, and starting from `Background` would have every collector begin by
     * tearing down the work it is about to need.
     */
    private val visibility = MutableStateFlow(AppVisibility.Foreground)

    /** The current visibility, then every change. Conflated — only the latest state matters. */
    fun events(): Flow<AppVisibility> = visibility.asStateFlow()

    /** Records a lifecycle transition. Called by the host's activity/fragment lifecycle hooks. */
    fun notify(next: AppVisibility) {
        visibility.value = next
    }
}
