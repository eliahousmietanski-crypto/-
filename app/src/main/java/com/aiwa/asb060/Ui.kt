package com.aiwa.asb060

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.keyButton(label: String, power: Boolean = false): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(getColor(R.color.text))
    textSize = if (power) 26f else 16f
    typeface = Typeface.DEFAULT_BOLD
    setBackgroundResource(if (power) R.drawable.key_power else R.drawable.key_bg)
    stateListAnimator = null
    gravity = Gravity.CENTER
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
}

fun Context.label(text: String, size: Float = 14f, muted: Boolean = false): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(getColor(if (muted) R.color.muted else R.color.text))
}

fun Context.weighted(height: Int, margin: Int = 6): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, dp(height), 1f).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) }

fun Context.fullWidth(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
