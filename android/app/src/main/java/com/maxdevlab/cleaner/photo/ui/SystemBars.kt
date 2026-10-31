package com.maxdevlab.cleaner.photo.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.maxdevlab.cleaner.photo.platform.enterFullscreen

/** AndroidX handles system-bar differences across supported Android versions. */
@Composable internal fun SystemBars(dark: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, dark) {
        val activity = generateSequence(view.context as Context?) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().firstOrNull()
        val dialog = generateSequence(view as View?) { it.parent as? View }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()
        val window = dialog?.window ?: activity?.window
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && !view.isInEditMode) window?.enterFullscreen()
        }
        if (!view.isInEditMode && window != null) {
            if (dialog == null) activity?.enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = if (dark) SystemBarStyle.dark(Color.BLACK) else SystemBarStyle.light(0xFFF2F2F7.toInt(), 0xFFF2F2F7.toInt())
            )
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            window.enterFullscreen()
            if (dialog != null) view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        }
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener) }
    }
}
