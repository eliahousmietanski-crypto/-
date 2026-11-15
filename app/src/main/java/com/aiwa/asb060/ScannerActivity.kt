package com.aiwa.asb060

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioManager
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

    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private lateinit var musicStatus: TextView
    private var autoStopOnMusic = true
    private var sentAt = 0L
    private val statusLoop: Runnable = object : Runnable {
        override fun run() { updateMusicStatus(); handler.postDelayed(this, 1000) }
    }

    /** Envoie un code, puis surveille la musique pendant l'intervalle avant de passer au suivant. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            sendCurrent()
            sentAt = System.currentTimeMillis()
            handler.postDelayed(watch, 100)
        }
    }

    private val watch: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (autoStopOnMusic && musicWasPlaying && !audio.isMusicActive) {
                musicStopped()
                return
            }
            updateMusicStatus()
            if (System.currentTimeMillis() - sentAt >= intervalMs) {
                if (!step(+1)) { stopAuto(); Toast.makeText(this@ScannerActivity, "Fin du balayage (FF)", Toast.LENGTH_SHORT).show(); return }
                render()
                handler.post(tick)
            } else handler.postDelayed(this, 100)
        }
    }

    private var musicWasPlaying = false

    private fun updateMusicStatus() {
        val on = audio.isMusicActive
        if (running) musicWasPlaying = musicWasPlaying || on
        musicStatus.text = if (on) "🎵 Musique en lecture sur le téléphone : le scan s'arrêtera tout seul si elle se met en pause"
            else "Aucune musique détectée sur le téléphone. Lancez une musique depuis le Poco (en Bluetooth vers la barre) pour l'arrêt automatique."
    }

    /** La musique vient de s'arrêter : le code qui vient de partir est le bon (ou celui d'avant). */
    private fun musicStopped() {
        stopAuto()
        val candidates = sent.take(2)
        val found = candidates.firstOrNull() ?: return
        AlertDialog.Builder(this)
            .setTitle("La musique s'est arrêtée !")
            .setMessage("Code envoyé juste avant : $found" +
                (if (candidates.size > 1) "\n(si ce n'est pas lui, c'est ${candidates[1]})" else "") +
                "\n\nRelancez la musique puis appuyez sur « Tester » pour vérifier qu'il remet bien en pause.")
            .setPositiveButton("Attribuer à une touche") { _, _ -> pickKey(IrCode.parse(found)!!) }
            .setNeutralButton("Tester") { _, _ -> testCode(found) }
            .setNegativeButton("Continuer le scan") { _, _ -> startAuto() }
            .setCancelable(false)
            .show()
    }

    private fun testCode(c: String) {
        val code = IrCode.parse(c) as IrCode.Nec
        address = code.address; extended = code.extended; command = code.command
        render()
        sendCurrent()
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
        updateMusicStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(statusLoop)
        handler.post(statusLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusLoop)
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

        val autoStop = android.widget.CheckBox(this).apply {
            text = "Arrêter le scan dès que la musique se met en pause"
            setTextColor(getColor(R.color.text))
            isChecked = autoStopOnMusic
            setOnCheckedChangeListener { _, c -> autoStopOnMusic = c }
        }
        root.addView(autoStop, fullWidth().apply { topMargin = dp(8) })
        musicStatus = label("", 12f, muted = true)
        root.addView(musicStatus, fullWidth())

        root.addView(keyButton("✔ Ça a réagi : attribuer à une touche…", power = false).apply {
            setBackgroundResource(R.drawable.key_power_rect)
            setOnClickListener { stopAuto(); assign() }
        }, LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(dp(4), dp(12), dp(4), dp(4)) })

        root.addView(label("Derniers codes envoyés (du plus récent au plus ancien) :", 13f, muted = true), fullWidth().apply { topMargin = dp(12) })
        history = label("—", 14f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(history, fullWidth())
        root.addView(label("Astuce : lancez une musique depuis le Poco, connecté en Bluetooth à la barre. Quand un code met la musique en pause (touche Lecture/Pause, Power, Source…), le scan s'arrête tout seul et vous propose d'attribuer le code.", 12f, muted = true), fullWidth().apply { topMargin = dp(10) })

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
        musicWasPlaying = audio.isMusicActive
        render()
        handler.post(tick)
    }

    private fun stopAuto() {
        running = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(watch)
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
