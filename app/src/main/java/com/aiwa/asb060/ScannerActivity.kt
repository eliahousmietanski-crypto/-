package com.aiwa.asb060

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Balaye les codes NEC pour retrouver ceux de la télécommande d'origine.
 * Mode « commande » : adresse fixe, la commande avance de 00 à FF.
 * Mode « adresse » : commande fixe, l'adresse avance de 00 à FF.
 */
class ScannerActivity : Activity() {
    private lateinit var ir: IrTransmitter
    private lateinit var store: CodeStore
    private val prefs by lazy { getSharedPreferences("scanner", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var address = 0x00
    private var extended = false
    private var command = 0x00
    private var sweepAddress = false
    private var intervalMs = 1500
    private var running = false

    private lateinit var current: TextView
    private lateinit var addrInput: EditText
    private lateinit var cmdInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var autoBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var history: TextView
    private val sent = ArrayDeque<String>()
    private var updatingFields = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sendCurrent()
            if (!step(+1)) { stopAuto(); Toast.makeText(this@ScannerActivity, "Fin du balayage (FF)", Toast.LENGTH_SHORT).show(); return }
            handler.postDelayed(this, intervalMs.toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = IrTransmitter(this)
        store = CodeStore(this)
        address = prefs.getInt("addr", 0)
        extended = prefs.getBoolean("ext", false)
        command = prefs.getInt("cmd", 0)
        sweepAddress = prefs.getBoolean("sweepAddr", false)
        intervalMs = prefs.getInt("interval", 1500)
        setContentView(buildUi())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    override fun onPause() {
        super.onPause()
        stopAuto()
        prefs.edit().putInt("addr", address).putBoolean("ext", extended).putInt("cmd", command)
            .putBoolean("sweepAddr", sweepAddress).putInt("interval", intervalMs).apply()
    }

    override fun onDestroy() {
        ir.release()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(label("Scanner de codes", 22f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(label("Visez la barre de son avec le haut du téléphone, barre allumée. Commencez par chercher VOL+ ou Power : c'est le plus visible.", 13f, muted = true), fullWidth().apply { bottomMargin = dp(12) })

        current = label("", 30f).apply {
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundResource(R.drawable.body_bg)
            setPadding(dp(8), dp(20), dp(8), dp(20))
        }
        root.addView(current, fullWidth())

        // Champs
        val fields = row().apply { setPadding(0, dp(12), 0, 0) }
        addrInput = hexField("Adresse")
        cmdInput = hexField("Commande")
        intervalInput = EditText(this).apply {
            hint = "ms"; setText(intervalMs.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(getColor(R.color.text)); setHintTextColor(getColor(R.color.muted))
        }
        fields.addView(column("Adresse (hex)", addrInput), LinearLayout.LayoutParams(0, -2, 1f))
        fields.addView(column("Commande (hex)", cmdInput), LinearLayout.LayoutParams(0, -2, 1f))
        fields.addView(column("Intervalle ms", intervalInput), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(fields, fullWidth())

        addrInput.addTextChangedListener(watcher { s ->
            val v = s.toIntOrNull(16) ?: return@watcher
            extended = s.length > 2
            address = if (extended) v and 0xFFFF else v and 0xFF
            renderCurrent()
        })
        cmdInput.addTextChangedListener(watcher { s ->
            command = (s.toIntOrNull(16) ?: return@watcher) and 0xFF
            renderCurrent()
        })
        intervalInput.addTextChangedListener(watcher { s ->
            intervalMs = (s.toIntOrNull() ?: return@watcher).coerceIn(300, 10000)
        })

        // Adresses courantes
        root.addView(label("Adresses fréquentes :", 13f, muted = true), fullWidth().apply { topMargin = dp(10) })
        val chips = row().apply { gravity = Gravity.START }
        for (a in listOf("00", "01", "02", "04", "08", "10", "20", "40", "80", "6E", "FF", "7F80", "FF00", "BF40")) {
            chips.addView(keyButton(a).apply {
                textSize = 13f
                setOnClickListener { setAddress(a); render() }
            }, LinearLayout.LayoutParams(dp(64), dp(42)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        root.addView(HorizontalScrollView(this).apply { addView(chips); isHorizontalScrollBarEnabled = false }, fullWidth())

        // Contrôles
        val ctl = row().apply { setPadding(0, dp(10), 0, 0) }
        ctl.addView(keyButton("◀").apply { setOnClickListener { stopAuto(); step(-1); render(); sendCurrent() } }, weighted(60, 4))
        ctl.addView(keyButton("Envoyer").apply { setOnClickListener { sendCurrent() } }, weighted(60, 4))
        ctl.addView(keyButton("▶").apply { setOnClickListener { stopAuto(); step(+1); render(); sendCurrent() } }, weighted(60, 4))
        root.addView(ctl, fullWidth())

        val ctl2 = row()
        autoBtn = keyButton("Auto ▶").apply { setOnClickListener { if (running) stopAuto() else startAuto() } }
        modeBtn = keyButton("").apply { textSize = 13f; setOnClickListener { sweepAddress = !sweepAddress; render() } }
        ctl2.addView(autoBtn, weighted(60, 4))
        ctl2.addView(modeBtn, weighted(60, 4))
        root.addView(ctl2, fullWidth())

        root.addView(keyButton("✔ Ça a réagi : attribuer à une touche…", power = false).apply {
            setBackgroundResource(R.drawable.key_power_rect)
            setOnClickListener { stopAuto(); assign() }
        }, LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(dp(4), dp(12), dp(4), dp(4)) })

        root.addView(label("Derniers codes envoyés (du plus récent au plus ancien) :", 13f, muted = true), fullWidth().apply { topMargin = dp(12) })
        history = label("—", 14f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(history, fullWidth())
        root.addView(label("Astuce : avec un intervalle de 1,5 s, la barre réagit souvent pendant que le code suivant part. Revenez avec ◀ et testez avec « Envoyer » pour être sûr.", 12f, muted = true), fullWidth().apply { topMargin = dp(10) })

        return ScrollView(this).apply { addView(root) }
    }

    private fun hexField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = android.graphics.Typeface.MONOSPACE
        setTextColor(getColor(R.color.text)); setHintTextColor(getColor(R.color.muted))
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
    }

    private fun column(title: String, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        addView(label(title, 12f, muted = true))
        addView(v)
    }

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { if (!updatingFields) onChange(s.toString().trim()) }
    }

    private fun setAddress(hex: String) {
        extended = hex.length > 2
        address = hex.toInt(16)
    }

    private fun code() = IrCode.Nec(address, extended, command)

    /** Avance (ou recule) d'un cran. @return false si on dépasse les bornes. */
    private fun step(d: Int): Boolean {
        if (sweepAddress) {
            val max = if (extended) 0xFFFF else 0xFF
            val n = address + d
            if (n < 0 || n > max) return false
            address = n
        } else {
            val n = command + d
            if (n < 0 || n > 0xFF) return false
            command = n
        }
        return true
    }

    private fun sendCurrent() {
        val c = code()
        ir.send(c)
        sent.addFirst(c.format())
        while (sent.size > 6) sent.removeLast()
        history.text = sent.joinToString("\n")
        render()
    }

    private fun startAuto() {
        running = true
        render()
        handler.post(tick)
    }

    private fun stopAuto() {
        running = false
        handler.removeCallbacks(tick)
        if (::autoBtn.isInitialized) render()
    }

    private fun renderCurrent() {
        current.text = code().format().removePrefix("NEC:").let {
            val p = it.split(":"); "Adr ${p[0]}  ·  Cmd ${p[1]}"
        }
    }

    private fun render() {
        renderCurrent()
        updatingFields = true
        val a = if (extended) String.format(Locale.US, "%04X", address) else String.format(Locale.US, "%02X", address)
        val c = String.format(Locale.US, "%02X", command)
        if (addrInput.text.toString() != a) addrInput.setText(a)
        if (cmdInput.text.toString() != c) cmdInput.setText(c)
        updatingFields = false
        autoBtn.text = if (running) "⏸ Pause" else "Auto ▶"
        modeBtn.text = if (sweepAddress) "Balaye : ADRESSE" else "Balaye : COMMANDE"
    }

    private fun assign() {
        val candidates = sent.toList().ifEmpty { listOf(code().format()) }.distinct()
        AlertDialog.Builder(this)
            .setTitle("Quel code a fonctionné ?")
            .setItems(candidates.toTypedArray()) { _, i -> pickKey(IrCode.parse(candidates[i])!!) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickKey(code: IrCode) {
        val keys = Keys.all
        val labels = keys.map { k -> k.label + (store.get(k)?.let { "   (${it.format()})" } ?: "") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Attribuer ${code.format()} à…")
            .setItems(labels) { _, i ->
                store.set(keys[i], code)
                Toast.makeText(this, "${code.format()} → ${keys[i].label}", Toast.LENGTH_SHORT).show()
                // On passe en mode commande sur cette adresse pour trouver les touches suivantes.
                if (code is IrCode.Nec && sweepAddress) { sweepAddress = false; render() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
