package com.aiwa.asb060

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var ir: IrTransmitter
    private lateinit var store: CodeStore
    private lateinit var status: TextView
    private lateinit var editBtn: Button
    private val buttons = mutableMapOf<String, Button>()
    private var editMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = IrTransmitter(this)
        store = CodeStore(this)
        setContentView(buildUi())
        if (!ir.available) {
            AlertDialog.Builder(this)
                .setTitle("Pas d'émetteur infrarouge")
                .setMessage("Ce téléphone ne semble pas avoir d'émetteur IR. Le Poco X5 5G en a un sur le bord supérieur : vérifiez que l'appli Mi Remote fonctionne.")
                .setPositiveButton("OK", null).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        ir.release()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Barre du haut
        val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label("AIWA", 22f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.25f })
        status = label("", 12f, muted = true)
        title.addView(status)
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(smallButton("Scanner") { startActivity(Intent(this, ScannerActivity::class.java)) })
        editBtn = smallButton("Éditer") { toggleEdit() }
        top.addView(editBtn)
        top.addView(smallButton("⋮") { showMenu(it) })
        root.addView(top, fullWidth())

        // Corps de la télécommande
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.body_bg)
            setPadding(dp(14), dp(18), dp(14), dp(18))
        }
        val first = row()
        first.addView(keyView(Keys.POWER, power = true), LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(6), 0, dp(6), dp(10)) })
        first.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        first.addView(keyView(Keys.MUTE), LinearLayout.LayoutParams(dp(110), dp(56)).apply { setMargins(dp(6), 0, dp(6), dp(10)) })
        body.addView(first, fullWidth())

        for (line in Keys.layout) {
            val r = row()
            for (k in line) {
                val h = if (k?.id?.startsWith("vol") == true) 72 else 56
                if (k == null) r.addView(View(this), weighted(h)) else r.addView(keyView(k), weighted(h))
            }
            body.addView(r, fullWidth())
        }
        body.addView(label("ASB-060", 12f, muted = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }, fullWidth())

        root.addView(body, fullWidth().apply { topMargin = dp(12) })
        return ScrollView(this).apply { addView(root); isFillViewport = true }
    }

    private fun smallButton(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(getColor(R.color.text))
        setBackgroundResource(R.drawable.key_bg)
        stateListAnimator = null
        minWidth = dp(48); minimumWidth = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(6) }
    }

    @Suppress("ClickableViewAccessibility")
    private fun keyView(key: RemoteKey, power: Boolean = false): Button {
        val b = keyButton(key.label, power)
        buttons[key.id] = b
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (!editMode) {
                        val code = store.get(key)
                        if (code != null) {
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (key.repeat) ir.startRepeat(code) else ir.send(code)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    ir.stopRepeat()
                    if (editMode) editKey(key)
                    else if (store.get(key) == null)
                        Toast.makeText(this, "« ${key.label} » n'a pas encore de code : utilisez Scanner ou Éditer.", Toast.LENGTH_SHORT).show()
                }
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; ir.stopRepeat() }
            }
            true
        }
        return b
    }

    private fun refresh() {
        val configured = Keys.all.count { store.get(it) != null }
        status.text = when {
            !ir.available -> "Émetteur IR introuvable"
            editMode -> "Mode édition : touchez une touche pour régler son code"
            else -> "$configured / ${Keys.all.size} touches configurées"
        }
        for (k in Keys.all) buttons[k.id]?.alpha = if (store.get(k) != null || editMode) 1f else 0.4f
        editBtn.text = if (editMode) "Terminé" else "Éditer"
    }

    private fun toggleEdit() {
        editMode = !editMode
        refresh()
    }

    private fun editKey(key: RemoteKey) {
        val input = EditText(this).apply {
            setText(store.get(key)?.format() ?: "NEC:00:00")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelectAllOnFocus(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(label("Formats : NEC:AA:CC (hex), NEC:AAAA:CC (adresse 16 bits), code Pronto « 0000 006D … » ou RAW:38000:9000,4500,…", 12f, muted = true))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Code de « ${key.label} »")
            .setView(box)
            .setPositiveButton("Enregistrer", null)
            .setNeutralButton("Tester", null)
            .setNegativeButton("Effacer") { _, _ -> store.set(key, null); refresh() }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = IrCode.parse(input.text.toString())
                if (c == null) input.error = "Code invalide" else { store.set(key, c); refresh(); dlg.dismiss() }
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val c = IrCode.parse(input.text.toString())
                if (c == null) input.error = "Code invalide" else ir.send(c)
            }
        }
        dlg.show()
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Exporter les codes (presse-papiers)")
        m.menu.add(0, 2, 1, "Importer les codes (presse-papiers)")
        m.menu.add(0, 3, 2, "Tout effacer")
        m.menu.add(0, 4, 3, "Aide")
        m.setOnMenuItemClickListener { item ->
            val cb = getSystemService(ClipboardManager::class.java)
            when (item.itemId) {
                1 -> {
                    cb.setPrimaryClip(ClipData.newPlainText("codes", store.exportJson()))
                    Toast.makeText(this, "Codes copiés dans le presse-papiers", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    val txt = cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                    val n = try { store.importJson(txt) } catch (e: Exception) { -1 }
                    Toast.makeText(this, if (n < 0) "Presse-papiers invalide" else "$n touche(s) importée(s)", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                3 -> AlertDialog.Builder(this).setMessage("Effacer tous les codes ?")
                    .setPositiveButton("Effacer") { _, _ -> store.clearAll(); refresh() }
                    .setNegativeButton("Annuler", null).show()
                4 -> showHelp()
            }
            true
        }
        m.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Comment ça marche")
            .setMessage(
                "Le Poco X5 5G a un émetteur infrarouge sur son bord supérieur : visez la barre de son avec le haut du téléphone.\n\n" +
                "Le téléphone ne peut pas « lire » la télécommande d'origine (il n'a pas de récepteur IR). Pour copier chaque touche :\n\n" +
                "1. Ouvrez Scanner, allumez la barre de son.\n" +
                "2. Lancez le balayage automatique : l'appli essaie les codes un par un.\n" +
                "3. Dès que la barre réagit (volume, source, extinction…), mettez en pause, revenez d'un ou deux codes pour trouver le bon, puis « Attribuer à une touche ».\n" +
                "4. Les autres touches ont presque toujours la même adresse : il suffit ensuite de balayer les commandes.\n\n" +
                "Si vous trouvez les codes en ligne (format Pronto ou NEC), collez-les via Éditer."
            )
            .setPositiveButton("OK", null).show()
    }
}
