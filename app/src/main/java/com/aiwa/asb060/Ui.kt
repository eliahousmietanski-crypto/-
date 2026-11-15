package com.aiwa.asb060

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Bouton à texte, fond arrondi. */
fun Context.textButton(label: String, background: Int = R.drawable.key_bg, size: Float = 15f): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(getColor(R.color.text))
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        setBackgroundResource(background)
        stateListAnimator = null
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
    }

/** Bouton à icône (touches de la télécommande). */
fun Context.iconButton(icon: Int, background: Int, description: String): ImageButton =
    ImageButton(this).apply {
        setImageResource(icon)
        setBackgroundResource(background)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
    }

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
}

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
}

fun Context.label(text: String, size: Float = 14f, muted: Boolean = false): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(getColor(if (muted) R.color.muted else R.color.text))
}

/** Case de largeur égale dans une rangée, avec [child] centré dedans. */
fun Context.cell(child: View, width: Int, height: Int): LinearLayout = LinearLayout(this).apply {
    gravity = Gravity.CENTER
    addView(child, LinearLayout.LayoutParams(width, height))
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}

fun Context.weighted(height: Int, margin: Int = 4): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, dp(height), 1f).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) }

fun fullWidth(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** Texte court pour afficher un code (les codes bruts sont trop longs pour l'écran). */
fun IrCode.short(): String = when (this) {
    is IrCode.Raw -> "Code brut (${pattern.size} valeurs)"
    else -> format()
}

/** Nom d'une touche sans la précision entre parenthèses. */
fun keyName(id: String?): String = when (id) {
    "mute" -> "Muet"
    else -> Keys.byId(id)?.label?.substringBefore(" (") ?: "touche inconnue"
}
