package org.vedareader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Window-scoped only. Restore the original bars/behaviour on exit, reader disposal or error. */
@Composable
@Suppress("DEPRECATION") // Compat behaviour setters also mutate legacy immersive flags on some devices.
internal fun ReaderFullscreen(active: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, view, lifecycle, active) {
        val activity = context.readerActivity()
        if (!active || activity == null) return@DisposableEffect onDispose { }
        val decor = activity.window.decorView
        val controller = WindowCompat.getInsetsController(activity.window, decor)
        val previousBehavior = controller.systemBarsBehavior
        val immersiveMask = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val previousImmersiveFlags = decor.systemUiVisibility and immersiveMask
        val previousInsets = ViewCompat.getRootWindowInsets(decor)
        val statusVisible = previousInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationVisible = previousInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        var attached = true
        val hide = Runnable {
            if (attached && !activity.isFinishing && !activity.isDestroyed) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        // Reapply only on window focus/resume, not every inset change: system edge gestures
        // must still be able to reveal transient bars for Back/Home and accessibility.
        val observer = decor.viewTreeObserver
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) decor.post(hide)
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) decor.post(hide)
        }
        observer.addOnWindowFocusChangeListener(focusListener)
        lifecycle.addObserver(lifecycleObserver)
        hide.run()
        onDispose {
            attached = false
            decor.removeCallbacks(hide)
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
            else decor.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            lifecycle.removeObserver(lifecycleObserver)
            controller.systemBarsBehavior = previousBehavior
            // Restoring BEHAVIOR_DEFAULT can leave SYSTEM_UI_FLAG_IMMERSIVE behind on
            // Android/compat combinations. Restore only our bits, not layout or light-bar flags.
            decor.systemUiVisibility = (decor.systemUiVisibility and immersiveMask.inv()) or previousImmersiveFlags
            if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

private fun Context.readerActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}