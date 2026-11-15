package com.aiwa.asb060

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Recherche automatique des codes : essaie les codes du plus probable au moins probable
 * (voir [ScanPlan]) et s'arrête toute seule quand la barre de son réagit (voir [ScanEngine]).
 */
class ScannerActivity : Activity(), ScanListener {
    private lateinit var ir: IrSender
    private lateinit var probe: Probe
    private lateinit var store: CodeStore
    private lateinit var db: ProfileDb
    private lateinit var engine: ScanEngine
    private val prefs by lazy { getSharedPreferences("search", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    /** Adresse quand la recherche est limitée à une seule adresse, sinon null. */
    private var singleAddress: String? = null
    private var ticks = 0
    private var resumed = false

    private lateinit var checks: TextView
    private lateinit var phaseText: TextView
    private lateinit var detailText: TextView
    private lateinit var codeText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var countText: TextView
    private lateinit var stateText: TextView
    private lateinit var startBtn: Button
    private lateinit var historyText: TextView
    private lateinit var advanced: LinearLayout
    private lateinit var intervalInput: EditText
    private lateinit var jumpRow: LinearLayout
    private lateinit var modeText: TextView

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            engine.tick()
            if (ticks++ % 10 == 0) renderChecks()
            handler.postDelayed(this, ScanEngine.TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = Deps.sender(this)
        probe = Deps.probe(this)
        store = CodeStore(this)
        db = ProfileDb.get(this)
        setContentView(buildUi())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val saved = prefs.getString(KEY_ADDRESS, null)
        val single = saved?.let { ScanPlan.singleAddress(it) }
        singleAddress = if (single != null) saved else null
        usePlan(single ?: ScanPlan.full(db), restore = true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ticks = 0
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        engine.stop()
        save()
        super.onPause()
    }

    override fun onDestroy() {
        ir.release()
        super.onDestroy()
    }

    private fun save() {
        prefs.edit()
            .putString(KEY_ADDRESS, singleAddress)
            .putInt(KEY_INDEX, engine.index)
            .putInt(KEY_SIZE, engine.plan.size)
            .putLong(KEY_INTERVAL, engine.intervalMs)
            .putBoolean(KEY_AUTO, engine.autoDetect)
            .apply()
    }

    /** Installe un plan de recherche ; [restore] reprend là où la recherche s'était arrêtée. */
    private fun usePlan(plan: ScanPlan, restore: Boolean) {
        val previous = if (::engine.isInitialized) engine else null
        previous?.stop()
        engine = ScanEngine(plan, ir, probe, Deps.clock, this)
        engine.intervalMs = previous?.intervalMs ?: prefs.getLong(KEY_INTERVAL, ScanEngine.DEFAULT_INTERVAL_MS)
        engine.autoDetect = previous?.autoDetect ?: prefs.getBoolean(KEY_AUTO, true)
        // On ne reprend la position que si la liste des essais est bien la même qu'à l'enregistrement.
        if (restore && prefs.getInt(KEY_SIZE, -1) == plan.size) {
            engine.index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, plan.size)
        }
        if (intervalInput.text.toString() != engine.intervalMs.toString()) intervalInput.setText(engine.intervalMs.toString())
        historyText.text = "—"
        buildJumpRow()
        render()
    }

    // ---------------------------------------------------------------- écran

    private fun buildUi(): View {
        val root = column().apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }

        root.addView(label("Recherche automatique", 22f).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(
            label(
                "1. Connectez le téléphone à la barre en Bluetooth et lancez une musique depuis le téléphone.\n" +
                    "2. Posez le téléphone, le haut pointé vers la barre.\n" +
                    "3. Appuyez sur Lancer. La recherche s'arrête toute seule dès que la barre réagit.",
                13f, muted = true,
            ),
            fullWidth().apply { topMargin = dp(4) },
        )
        checks = label("", 13f)
        root.addView(checks, fullWidth().apply { topMargin = dp(10) })

        // Carte : essai en cours
        val card = column().apply {
            setBackgroundResource(R.drawable.body_bg)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        phaseText = label("", 12f, muted = true)
        detailText = label("", 17f).apply { typeface = Typeface.DEFAULT_BOLD }
        codeText = label("", 22f).apply { typeface = Typeface.MONOSPACE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        countText = label("", 12f, muted = true)
        card.addView(phaseText, fullWidth())
        card.addView(detailText, fullWidth().apply { topMargin = dp(6) })
        card.addView(codeText, fullWidth().apply { topMargin = dp(2) })
        card.addView(progress, fullWidth().apply { topMargin = dp(8) })
        card.addView(countText, fullWidth())
        root.addView(card, fullWidth().apply { topMargin = dp(12) })

        stateText = label("", 14f).apply { setTextColor(getColor(R.color.ok)) }
        root.addView(stateText, fullWidth().apply { topMargin = dp(8) })

        startBtn = textButton("", R.drawable.key_accent, 17f).apply {
            setOnClickListener { if (engine.running) engine.stop() else engine.start() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(6) })

        val manual = row()
        manual.addView(textButton("◀ Précédent", size = 13f).apply {
            setOnClickListener { engine.sendManual(engine.index - 2) }
        }, weighted(52))
        manual.addView(textButton("Renvoyer", size = 13f).apply {
            setOnClickListener { engine.sendManual(engine.index - 1) }
        }, weighted(52))
        manual.addView(textButton("Suivant ▶", size = 13f).apply {
            setOnClickListener { engine.sendManual(engine.index) }
        }, weighted(52))
        root.addView(manual, fullWidth().apply { topMargin = dp(6) })

        root.addView(textButton("✔ La barre a réagi !", R.drawable.key_bg, 16f).apply {
            setOnClickListener { showCandidates(engine.manualDetection()) }
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })

        root.addView(label("Derniers codes envoyés (le plus récent en haut) :", 13f, muted = true), fullWidth().apply { topMargin = dp(14) })
        historyText = label("—", 13f).apply { typeface = Typeface.MONOSPACE }
        root.addView(historyText, fullWidth())

        // Réglages avancés (repliés)
        root.addView(textButton("Réglages avancés", size = 13f).apply {
            setOnClickListener { advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(16) })
        advanced = column().apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        root.addView(advanced, fullWidth())

        advanced.addView(label("Temps entre deux essais, en millisecondes (800 conseillé) :", 13f, muted = true))
        intervalInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            setTextColor(getColor(R.color.text))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s.toString().toLongOrNull() ?: return
                    if (::engine.isInitialized) {
                        engine.intervalMs = v.coerceIn(ScanEngine.MIN_INTERVAL_MS, ScanEngine.MAX_INTERVAL_MS)
                        render()
                    }
                }
            })
        }
        advanced.addView(intervalInput, LinearLayout.LayoutParams(dp(120), -2))

        advanced.addView(CheckBox(this).apply {
            text = "S'arrêter tout seul quand la barre réagit"
            setTextColor(getColor(R.color.text))
            isChecked = prefs.getBoolean(KEY_AUTO, true)
            setOnCheckedChangeListener { _, c -> engine.autoDetect = c; renderChecks() }
        }, fullWidth())

        advanced.addView(label("Aller directement à une étape :", 13f, muted = true), fullWidth().apply { topMargin = dp(10) })
        jumpRow = row().apply { gravity = Gravity.LEFT }
        advanced.addView(jumpRow, fullWidth())

        modeText = label("", 13f, muted = true)
        advanced.addView(modeText, fullWidth().apply { topMargin = dp(10) })
        val modes = row().apply { gravity = Gravity.LEFT }
        modes.addView(textButton("Une seule adresse…", size = 13f).apply {
            setOnClickListener { askAddress() }
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) })
        modes.addView(textButton("Recherche complète", size = 13f).apply {
            setOnClickListener { singleAddress = null; usePlan(ScanPlan.full(db), restore = false) }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        advanced.addView(modes, fullWidth())

        return ScrollView(this).apply { addView(root) }
    }

    private fun buildJumpRow() {
        jumpRow.removeAllViews()
        for ((n, start) in engine.plan.phaseStarts.withIndex()) {
            jumpRow.addView(textButton("Étape ${n + 1}", size = 13f).apply {
                setOnClickListener { restartAt(start) }
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) })
        }
    }

    /** Repart de l'essai [index], historique effacé. */
    private fun restartAt(index: Int) {
        val plan = engine.plan
        usePlan(plan, restore = false)
        engine.index = index.coerceIn(0, plan.size)
        render()
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            hint = "20"
            setText(singleAddress ?: (engine.lastSent?.code as? IrCode.Nec)?.addressText ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val box = column().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label("Adresse en hexadécimal : 2 chiffres (par exemple 20) ou 4 chiffres.", 13f, muted = true))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Chercher sur une seule adresse")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> searchAddress(input.text.toString()) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun searchAddress(address: String) {
        val plan = ScanPlan.singleAddress(address)
        if (plan == null) {
            Toast.makeText(this, "Adresse invalide", Toast.LENGTH_SHORT).show()
            return
        }
        singleAddress = address.trim().uppercase(Locale.US)
        usePlan(plan, restore = false)
    }

    // ------------------------------------------------------------ affichage

    private fun render() {
        val plan = engine.plan
        val last = engine.lastSent
        val shownIndex = (if (last != null) engine.index - 1 else engine.index).coerceIn(0, maxOf(0, plan.size - 1))
        val step = last ?: plan[shownIndex]
        val bounds = plan.groupBounds(shownIndex)
        val position = shownIndex - bounds.first + 1
        val count = bounds.last - bounds.first + 1
        val group = if (plan.inSweep(shownIndex)) "pour cette adresse" else "de cette étape"

        phaseText.text = "Étape ${step.phase + 1} sur ${plan.phaseTitles.size} · ${plan.phaseTitles[step.phase]}"
        detailText.text = if (last == null) "Prochain essai : ${step.detail}" else step.detail
        codeText.text = step.code.short()
        progress.max = count
        progress.progress = if (last == null) position - 1 else position
        val minutes = (count - position) * engine.intervalMs / 60000.0
        countText.text = "Essai $position sur $count $group" + when {
            minutes < 1 -> ""
            minutes < 90 -> String.format(Locale.FRANCE, " · encore environ %.0f min", minutes)
            else -> String.format(Locale.FRANCE, " · encore environ %.0f h", minutes / 60)
        }

        val state = engine.statusText
        stateText.text = state ?: ""
        stateText.visibility = if (state == null) View.GONE else View.VISIBLE
        startBtn.text = when {
            engine.running -> "Pause"
            engine.index == 0 -> "▶  Lancer la recherche"
            else -> "▶  Reprendre la recherche"
        }
        modeText.text = singleAddress?.let { "Recherche limitée à l'adresse $it." } ?: "Recherche complète (toutes les adresses)."
        renderChecks()
    }

    private fun renderChecks() {
        val bt = probe.btConnected()
        val music = probe.musicActive()
        checks.text = when {
            !engine.autoDetect -> "○ Arrêt automatique désactivé : appuyez sur « La barre a réagi » quand elle réagit."
            !bt -> "○ Barre non connectée en Bluetooth : la recherche ne pourra pas s'arrêter toute seule."
            !music -> "● Bluetooth connecté.\n○ Lancez maintenant une musique depuis le téléphone."
            else -> "● Bluetooth connecté.\n● Musique en lecture : la recherche s'arrêtera toute seule."
        }
        checks.setTextColor(getColor(if (engine.autoDetect && bt && music) R.color.ok else R.color.text))
    }

    // ------------------------------------------------- réactions du moteur

    override fun onSent(step: Step, verifying: Boolean) {
        historyText.text = engine.recent.joinToString("\n") { step ->
            step.code.short() + (step.profile?.let { "  ·  ${it.name}" } ?: "")
        }.ifEmpty { "—" }
        render()
    }

    override fun onChanged() = render()

    override fun onFalseAlarm() {
        Toast.makeText(this, "Fausse alerte : aucun code ne refait l'effet. La recherche continue.", Toast.LENGTH_LONG).show()
    }

    override fun onFinished() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Recherche terminée")
            .setMessage(
                "Tous les codes de cette recherche ont été essayés sans réaction repérée.\n\n" +
                    "Vérifiez que le haut du téléphone pointait bien vers la barre, puis recommencez, " +
                    "ou essayez une autre adresse dans les réglages avancés."
            )
            .setPositiveButton("Recommencer") { _, _ -> restartAt(0) }
            .setNegativeButton("Fermer", null).show()
    }

    override fun onDetected(detection: Detection) {
        if (isFinishing) return
        if (detection.confirmed && detection.candidates.size == 1 && detection.type != Detection.Type.DISCONNECT) {
            chosen(detection.candidates[0], detection)
        } else {
            showCandidates(detection)
        }
    }

    /** Liste les codes suspects : l'utilisateur les teste et désigne le bon. */
    private fun showCandidates(d: Detection) {
        if (d.candidates.isEmpty()) {
            Toast.makeText(this, "Aucun code n'a encore été envoyé.", Toast.LENGTH_SHORT).show()
            return
        }
        val (title, text) = when (d.type) {
            Detection.Type.PAUSE -> Pair(
                "La musique s'est arrêtée",
                "Je n'ai pas pu vérifier tout seul quel code en est la cause. Relancez la musique, puis appuyez sur " +
                    "« Tester » à côté de chaque code : celui qui la remet en pause est le bon.",
            )
            Detection.Type.DISCONNECT -> Pair(
                "La barre a réagi : Bluetooth coupé",
                "La barre s'est éteinte ou a changé de source juste après l'un de ces codes.\n\n" +
                    "• Si elle est éteinte, « Tester » sur le bon code la rallume : c'est la touche Power.\n" +
                    "• Si elle a changé de source (le voyant a changé de couleur), c'est la touche MODE.\n\n" +
                    "Appuyez sur « C'est lui » à côté du code qui fait l'effet.",
            )
            Detection.Type.VOLUME_UP, Detection.Type.VOLUME_DOWN -> Pair(
                "Le volume a changé",
                "Appuyez sur « Tester » à côté de chaque code pour voir lequel change le volume.",
            )
            Detection.Type.MANUAL -> Pair(
                "Quel code a fait réagir la barre ?",
                "Voici les derniers codes envoyés. Appuyez sur « Tester » pour les renvoyer un par un, puis sur " +
                    "« C'est lui » à côté de celui qui fait réagir la barre.",
            )
        }
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(label(text, 14f))
        var dialog: AlertDialog? = null
        for (step in d.candidates) {
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
            val info = column()
            info.addView(label(step.code.short(), 15f).apply { typeface = Typeface.MONOSPACE })
            known(step, d.type)?.let { info.addView(label(it, 12f, muted = true)) }
            line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(textButton("Tester", size = 13f).apply {
                setOnClickListener { ir.send(step.code) }
            }, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(6) })
            line.addView(textButton("C'est lui", R.drawable.key_accent, 13f).apply {
                setOnClickListener { dialog?.dismiss(); chosen(step, d) }
            }, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(6) })
            box.addView(line, fullWidth().apply { topMargin = dp(12) })
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Continuer la recherche") { _, _ -> engine.start() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Les télécommandes connues qui ont ce code sur une touche qui colle avec l'effet observé. */
    private fun agreeing(step: Step, type: Detection.Type): List<Pair<Profile, String>> {
        val all = db.matches(step.code).filter { agrees(type, it.second) }
        // En tête : la télécommande pour laquelle ce code a été essayé.
        return all.sortedBy { it.first !== step.profile }
    }

    /** Ce qu'on sait d'un code grâce aux télécommandes connues. */
    private fun known(step: Step, type: Detection.Type): String? {
        val (profile, function) = agreeing(step, type).firstOrNull() ?: db.matches(step.code).firstOrNull() ?: return null
        return "connu : « ${keyName(function)} » sur ${profile.name}"
    }

    /**
     * La télécommande identique, recopiée sur l'adresse de [step] : même télécommande, mais
     * réglée en usine pour une autre marque. Null si [step] ne colle pas avec cette hypothèse.
     */
    private fun twinOnAddressOf(step: Step, type: Detection.Type): Profile? {
        val twin = db.twin ?: return null
        val nec = step.code as? IrCode.Nec ?: return null
        if (!nec.standardCommand) return null
        val twinCodes = twin.codes.mapValues { it.value as? IrCode.Nec ?: return null }
        if (twinCodes.values.any { !it.standardCommand }) return null
        if (twinCodes.values.first().addressText == nec.addressText) return null
        // Le code trouvé doit être, sur la télécommande identique, une touche qui fait l'effet observé.
        if (twinCodes.none { it.value.c0 == nec.c0 && agrees(type, it.key) }) return null
        val moved = twinCodes.mapValues { IrCode.Nec(nec.a0, nec.a1, it.value.c0, it.value.c1) as IrCode }
        return Profile("${twin.name} · adresse ${nec.addressText}", twin.category, twin.score, twin.matched, twin.total, false, moved)
    }

    /** La touche que l'effet observé désigne, si on peut le dire. */
    private fun observedKey(type: Detection.Type): String? = when (type) {
        Detection.Type.PAUSE -> Keys.PLAY.id
        Detection.Type.VOLUME_UP -> Keys.VOL_UP.id
        Detection.Type.VOLUME_DOWN -> Keys.VOL_DOWN.id
        else -> null
    }

    /** Vrai si la touche [function] d'une télécommande connue colle avec l'effet observé. */
    private fun agrees(type: Detection.Type, function: String): Boolean = when (type) {
        Detection.Type.PAUSE -> function == Keys.PLAY.id
        Detection.Type.VOLUME_UP -> function == Keys.VOL_UP.id
        Detection.Type.VOLUME_DOWN -> function == Keys.VOL_DOWN.id
        Detection.Type.DISCONNECT -> function == Keys.POWER.id || function == Keys.MODE.id
        Detection.Type.MANUAL -> true
    }

    /** Le bon code est connu : propose la télécommande entière, sinon range ce seul code. */
    private fun chosen(step: Step, d: Detection) {
        val effect = when (d.type) {
            Detection.Type.PAUSE -> "met la musique en pause"
            Detection.Type.VOLUME_UP -> "monte le volume"
            Detection.Type.VOLUME_DOWN -> "baisse le volume"
            Detection.Type.DISCONNECT -> "éteint la barre ou change sa source"
            Detection.Type.MANUAL -> "fait réagir la barre"
        }
        val checked = if (d.confirmed) " (vérifié : renvoyé une 2ᵉ fois, il a refait le même effet)" else ""
        val found = "Le code ${step.code.short()} $effect$checked."
        val suggested = observedKey(d.type) ?: step.function

        // Télécommandes entières qui expliquent ce code, de la plus probable à la moins probable.
        val options = ArrayList<Pair<Profile, String>>()
        for ((profile, function) in agreeing(step, d.type).take(3)) {
            options += Pair(profile, "C'est la touche « ${keyName(function)} » de la télécommande « ${profile.name} » (${profile.description}).")
        }
        twinOnAddressOf(step, d.type)?.let {
            options += Pair(it, "Ce serait la télécommande identique à la vôtre, mais réglée sur l'adresse ${(step.code as IrCode.Nec).addressText}.")
        }
        if (options.isEmpty()) {
            pickKey(step, suggested, found)
            return
        }

        val box = column().apply { setPadding(dp(20), dp(8), dp(20), dp(4)) }
        box.addView(label("$found\n\nJe peux charger d'un coup toutes les touches de la télécommande qui correspond :", 14f))
        var dialog: AlertDialog? = null
        for ((profile, why) in options) {
            box.addView(label(why, 12f, muted = true), fullWidth().apply { topMargin = dp(12) })
            box.addView(textButton("Charger les ${profile.keyCount} touches", R.drawable.key_accent, 14f).apply {
                setOnClickListener {
                    dialog?.dismiss()
                    val n = store.loadProfile(profile, verified = d.confirmed)
                    Toast.makeText(this@ScannerActivity, "$n touches chargées (${profile.name}). Essayez-les !", Toast.LENGTH_LONG).show()
                    finish()
                }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4) })
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Code trouvé !")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Seulement cette touche") { _, _ -> pickKey(step, suggested, null) }
            .show()
    }

    /** Range [step] sur une touche de la télécommande. */
    private fun pickKey(step: Step, suggested: String?, intro: String?) {
        val keys = Keys.all
        val labels = keys.map { k -> k.label + (store.get(k)?.let { "   — déjà : ${it.short()}" } ?: "") }.toTypedArray()
        var selected = keys.indexOfFirst { it.id == suggested }
        // Titre sur mesure : le titre standard est coupé au bout de deux lignes.
        val title = column().apply {
            setPadding(dp(24), dp(20), dp(24), dp(4))
            addView(label("Sur quelle touche ranger ${step.code.short()} ?", 18f).apply { typeface = Typeface.DEFAULT_BOLD })
            if (intro != null) addView(label(intro, 13f, muted = true), fullWidth().apply { topMargin = dp(6) })
        }
        AlertDialog.Builder(this)
            .setCustomTitle(title)
            .setSingleChoiceItems(labels, selected) { _, i -> selected = i }
            .setPositiveButton("Enregistrer") { _, _ ->
                if (selected < 0) {
                    Toast.makeText(this, "Aucune touche choisie.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.set(keys[selected], step.code)
                store.profileName = null
                store.verified = true
                Toast.makeText(this, "${step.code.short()} → ${keyName(keys[selected].id)}", Toast.LENGTH_SHORT).show()
                offerSameAddress(step)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Une touche est trouvée : les autres ont presque toujours la même adresse. */
    private fun offerSameAddress(step: Step) {
        val nec = step.code as? IrCode.Nec ?: return
        if (!nec.standardCommand || singleAddress == nec.addressText) return
        AlertDialog.Builder(this)
            .setTitle("Chercher les autres touches ?")
            .setMessage(
                "Les autres touches utilisent presque toujours la même adresse (${nec.addressText}).\n\n" +
                    "Je peux essayer ses 256 commandes une par une. Avancez avec « Suivant ▶ » en regardant et en " +
                    "écoutant la barre ; quand elle réagit, appuyez sur « La barre a réagi »."
            )
            .setPositiveButton("Chercher les autres touches") { _, _ -> searchAddress(nec.addressText) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_INDEX = "index"
        const val KEY_SIZE = "size"
        const val KEY_INTERVAL = "interval"
        const val KEY_AUTO = "auto"
    }
}
