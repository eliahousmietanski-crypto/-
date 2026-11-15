package com.aiwa.asb060

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

fun allViews(root: View): List<View> {
    val out = ArrayList<View>()
    fun walk(v: View) {
        out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(root)
    return out
}

fun textOf(root: View): String = allViews(root).filterIsInstance<TextView>()
    .filter { it.visibility == View.VISIBLE && generateSequence(it.parent) { it.parent }.filterIsInstance<View>().all { p -> p.visibility == View.VISIBLE } }
    .joinToString("\n") { it.text.toString() }

fun byText(root: View, text: String): View =
    allViews(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
        ?: throw AssertionError("Pas de vue « $text » dans :\n" + textOf(root))

fun byDescription(root: View, description: String): View =
    allViews(root).firstOrNull { it.contentDescription?.toString() == description }
        ?: throw AssertionError("Pas de vue décrite « $description »")

fun idle() = shadowOf(Looper.getMainLooper()).idle()

fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

/** Appui bref du doigt sur une vue. */
fun tap(v: View) {
    val t = SystemClock.uptimeMillis()
    v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
    v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, 5f, 5f, 0))
    idle()
}

fun click(v: View) {
    v.performClick()
    idle()
}

fun latestDialog(): AlertDialog = (ShadowAlertDialog.getLatestAlertDialog() as? AlertDialog)
    ?: throw AssertionError("Aucune boîte de dialogue affichée")

fun dialogTitle(d: AlertDialog): String = shadowOf(d).title?.toString().orEmpty()

fun dialogText(d: Dialog): String = textOf(d.window!!.decorView)

fun noDialogShowing(): Boolean = ShadowDialog.getShownDialogs().none { it.isShowing }

/** Enregistre l'image d'une vue (pour contrôler la mise en page à l'œil). */
fun shot(v: View, name: String) {
    val dir = System.getProperty("shots.dir") ?: return
    if (v.width == 0 || v.height == 0) return
    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    v.draw(Canvas(bmp))
    File(dir).mkdirs()
    FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
