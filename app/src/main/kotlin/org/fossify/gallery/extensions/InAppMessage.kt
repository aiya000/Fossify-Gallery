package org.fossify.gallery.extensions

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.isOnMainThread
import org.fossify.gallery.R
import org.fossify.gallery.helpers.InAppMessagePolicy
import kotlin.math.roundToInt

// The app's own messages, in place of the OS toast (#76).
//
// A toast is the OS speaking: black on white, two lines at most, and gone before a sentence of
// guidance has been read. What the app has to say is said on its own screen instead, in its own
// colours, with the room a sentence needs. It is said as a toast only where there is no screen
// of the app to say it on: a service speaking while no screen of ours is up, a screen on its
// way out, a message raised before the screen has a view. Those call toast() themselves, and say
// why next to the call.
//
// Three places a message can land:
//
// - Context.showInAppMessage(): on the activity behind the context when it is on screen, else a
//   toast. Most of the app says things through this one -- from an activity, from an adapter or
//   a storage that was handed one, from an extension on a Context that turns out to be one
// - View.showInAppMessage(): in the window this view is in. A dialog is a window of its own, so
//   what goes on inside it (the connection test of a share, a folder the picker turns away) is
//   said inside it, not on the dimmed screen behind
// - TextInputLayout.showInputError(): under the field it is about, for what a dialog has to say
//   about a name or a number that was typed. The field keeps the words until it is edited
//
// Every message is logged under IN_APP_MESSAGE_TAG with where it went. That line is what the
// device tests read: a bar is up for seconds, a dump takes seconds, and a toast was never caught
// in time (agents/tests/a-toast-is-gone-before-the-dump.md)

const val IN_APP_MESSAGE_TAG = "InAppMessage"

private const val MAX_LINES = 5
private const val CORNER_RADIUS_DP = 12f
private const val MARGIN_DP = 16f
private const val STROKE_DP = 1f

// how far the bar is tinted from the screen's background towards the primary colour: enough to
// read as the app's own, not so far that the text has to fight it
private const val SURFACE_TINT = 0.22f
private const val STROKE_ALPHA = 0.6f

// A screen whose bottom is a bar of its own -- the viewer's actions, the editor's tools -- names
// it here, and a message is laid above the bar instead of across it
interface InAppMessageAnchor {
    fun inAppMessageAnchor(): View?
}

fun Context.showInAppMessage(id: Int, length: Int = Toast.LENGTH_SHORT) = showInAppMessage(getString(id), length)

fun Context.showInAppMessage(text: String, length: Int = Toast.LENGTH_SHORT) {
    onMainThread {
        val activity = this as? Activity
        val host = activity?.inAppMessageHost()
        if (host == null) {
            Log.d(IN_APP_MESSAGE_TAG, "Said as a toast, with no screen of the app to say it on: $text")
            toast(text, length)
        } else {
            show(host, text, length, (activity as? InAppMessageAnchor)?.inAppMessageAnchor())
        }
    }
}

fun Context.showInAppError(e: Exception) = showInAppError(e.toString())

// the words showErrorToast() used, so that an error reads as it always did
fun Context.showInAppError(message: String) = showInAppMessage(errorMessage(message), Toast.LENGTH_LONG)

fun View.showInAppMessage(id: Int, length: Int = Toast.LENGTH_SHORT) = showInAppMessage(context.getString(id), length)

fun View.showInAppMessage(text: String, length: Int = Toast.LENGTH_SHORT) {
    onMainThread {
        if (isAttachedToWindow) {
            show(this, text, length, anchorOfItsScreen())
        } else {
            // the dialog is gone already; what it had to say goes where its screen says things
            context.showInAppMessage(text, length)
        }
    }
}

fun View.showInAppError(e: Exception) = showInAppMessage(context.errorMessage(e.toString()), Toast.LENGTH_LONG)

// the screen's own anchor is for a view of the screen; a dialog over it is a window of its own,
// with nothing of the screen's along its bottom
private fun View.anchorOfItsScreen(): View? {
    val activity = context as? Activity ?: return null
    if (activity !is InAppMessageAnchor || rootView !== activity.window.decorView) {
        return null
    }

    return activity.inAppMessageAnchor()
}

// What a dialog has to say about one of its fields, said under that field. The field is focused
// so that typing goes to the right place, and the words are taken down with the first edit
fun TextInputLayout.showInputError(id: Int) {
    val text = context.getString(id)
    Log.d(IN_APP_MESSAGE_TAG, "Said under the field: $text")
    error = text
    editText?.requestFocus()
    if (getTag(R.id.in_app_message_error_clearer) == null) {
        setTag(R.id.in_app_message_error_clearer, true)
        editText?.doAfterTextChanged { error = null }
    }
}

private fun Context.errorMessage(message: String) = getString(org.fossify.commons.R.string.error, message)

// Where a message of this activity lands: its CoordinatorLayout when its layout is one -- there
// the bar can be swiped away and sits in the layout's own flow -- else its content view. Null
// when the activity is on its way out or has no view yet, which is when a toast is right
private fun Activity.inAppMessageHost(): View? {
    if (isFinishing || isDestroyed) {
        return null
    }

    val content = findViewById<ViewGroup>(android.R.id.content) ?: return null
    if (!content.isAttachedToWindow) {
        return null
    }

    return content.getChildAt(0) as? CoordinatorLayout ?: content
}

private fun show(host: View, text: String, length: Int, anchor: View?) {
    Log.d(IN_APP_MESSAGE_TAG, "Said in the app: $text")
    val context = host.context
    val density = context.resources.displayMetrics.density
    val primary = context.getProperPrimaryColor()
    val surface = ColorUtils.blendARGB(context.getProperBackgroundColor(), primary, SURFACE_TINT)
    val margin = (MARGIN_DP * density).roundToInt()

    Snackbar.make(host, text, Snackbar.LENGTH_LONG).apply {
        duration = InAppMessagePolicy.durationMillis(text.length, wasLong = length == Toast.LENGTH_LONG)
        animationMode = BaseTransientBottomBar.ANIMATION_MODE_FADE
        anchor?.takeIf { it.isVisible }?.let { anchorView = it }
        // the material layout tints whatever background it is handed with the theme's own colour, which
        // is what hid the colours below the first time; the tint is taken off first
        view.backgroundTintList = null
        view.background = GradientDrawable().apply {
            cornerRadius = CORNER_RADIUS_DP * density
            setColor(surface)
            setStroke((STROKE_DP * density).roundToInt().coerceAtLeast(1), primary.adjustAlpha(STROKE_ALPHA))
        }
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(margin, margin, margin, margin)
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
            maxLines = MAX_LINES
            setTextColor(surface.getContrastColor())
        }
        show()
    }
}

private fun onMainThread(block: () -> Unit) {
    if (isOnMainThread()) {
        block()
    } else {
        Handler(Looper.getMainLooper()).post(block)
    }
}
