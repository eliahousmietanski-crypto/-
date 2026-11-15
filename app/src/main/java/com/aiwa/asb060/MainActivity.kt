package com.aiwa.asb060

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** La télécommande : mêmes touches, au même endroit, que celle d'origine de l'ASB-060. */
class MainActivity : Activity() {
    private lateinit var ir: IrSender
    private lateinit var store: CodeStore
    private lateinit var db: ProfileDb

    private lateinit var status: TextView
    private lateinit var editBtn: Button
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButtons: LinearLayout
    private val keyViews = mutableMapOf<String, View>()
    private var editMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = Deps.sender(this)
        store = CodeStore(this)
        db = ProfileDb.get(this)
        setContentView(buildUi())
        firstLaunch()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        ir.stopHold()
        super.onPause()
    }

    override fun onDestroy() {
        ir.release()
        super.onDestroy()
    }

    /** Au premier lancement, charge les codes de la télécommande identique. */
    private fun firstLaunch() {
        val twin = db.twin
        if (store.welcomed || twin == null) {
            if (!ir.available) warnNoEmitter()
            return
        }
        store.welcomed = true
        if (store.configuredCount == 0) {
            store.loadProfile(twin, verified = false)
            AlertDialog.Builder(this)
                .setTitle("Codes probables chargés")
                .setMessage(
                    "La télécommande de l'ASB-060 est la même que celle d'une autre barre de son, " +
                        "la ${twin.name}, dont les codes sont connus. Je les ai mis sur les 12 touches.\n\n" +
                        "Visez la barre avec le haut du téléphone et appuyez plusieurs fois sur VOL +, ou sur Power.\n\n" +
                        "Si rien ne réagit, appuyez sur « Ça ne marche pas » : l'appli cherchera les bons codes toute seule."
                )
                .setPositiveButton("OK", null).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Codes probables trouvés")
                .setMessage(
                    "La télécommande de l'ASB-060 est la même que celle d'une autre barre de son, " +
                        "la ${twin.name}, dont les codes sont connus.\n\n" +
                        "Les charger à la place des codes actuels ?"
                )
                .setPositiveButton("Charger") { _, _ -> loadProfile(twin) }
                .setNegativeButton("Garder mes codes", null).show()
        }
    }

    private fun warnNoEmitter() {
        AlertDialog.Builder(this)
            .setTitle("Pas d'émetteur infrarouge")
            .setMessage(
                "Ce téléphone ne semble pas avoir d'émetteur infrarouge. Le Poco X5 5G en a un sur son bord " +
                    "supérieur : vérifiez que l'appli Mi Remote fonctionne."
            )
            .setPositiveButton("OK", null).show()
    }

    private fun buildUi(): View {
        val root = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }

        // Barre du haut
        val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = column()
        titles.addView(label("Aiwa ASB-060", 20f).apply { typeface = Typeface.DEFAULT_BOLD })
        status = label("", 12f, muted = true)
        titles.addView(status)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(topButton("Recherche") { openSearch() })
        editBtn = topButton("Éditer") { toggleEdit() }
        top.addView(editBtn)
        top.addView(topButton("⋮") { showMenu(it) })
        root.addView(top, fullWidth())

        // Bandeau d'aide
        banner = column().apply {
            setBackgroundResource(R.drawable.banner_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bannerText = label("", 13f)
        banner.addView(bannerText, fullWidth())
        bannerButtons = row().apply { gravity = Gravity.END }
        banner.addView(bannerButtons, fullWidth().apply { topMargin = dp(8) })
        root.addView(banner, fullWidth().apply { topMargin = dp(10) })

        // Corps de la télécommande
        val body = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.body_bg)
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }

        val first = row().apply { gravity = Gravity.CENTER_VERTICAL }
        first.addView(iconKey(Keys.POWER, R.drawable.ic_power, R.drawable.key_power), LinearLayout.LayoutParams(dp(60), dp(60)))
        first.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        first.addView(textKey(Keys.MODE, "MODE"), LinearLayout.LayoutParams(dp(96), dp(48)))
        body.addView(first, fullWidth())

        // Couronne : volume en haut et en bas, pistes à gauche et à droite, lecture/pause au centre
        val ring = FrameLayout(this).apply { setBackgroundResource(R.drawable.ring_bg) }
        ring.addView(
            iconKey(Keys.VOL_UP, R.drawable.ic_vol_up, R.drawable.key_ghost),
            FrameLayout.LayoutParams(dp(92), dp(68), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) },
        )
        ring.addView(
            iconKey(Keys.VOL_DOWN, R.drawable.ic_vol_down, R.drawable.key_ghost),
            FrameLayout.LayoutParams(dp(92), dp(68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(4) },
        )
        ring.addView(
            iconKey(Keys.PREV, R.drawable.ic_prev, R.drawable.key_ghost),
            FrameLayout.LayoutParams(dp(68), dp(92), Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(4) },
        )
        ring.addView(
            iconKey(Keys.NEXT, R.drawable.ic_next, R.drawable.key_ghost),
            FrameLayout.LayoutParams(dp(68), dp(92), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(4) },
        )
        ring.addView(
            iconKey(Keys.PLAY, R.drawable.ic_play_pause, R.drawable.key_center),
            FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER),
        )
        body.addView(ring, LinearLayout.LayoutParams(dp(232), dp(232)).apply { topMargin = dp(12) })

        // Basses à gauche, aigus à droite, EQ entre les deux
        val round = dp(56)
        val ups = row()
        ups.addView(cell(iconKey(Keys.BASS_UP, R.drawable.ic_up, R.drawable.key_round), round, round))
        ups.addView(cell(iconKey(Keys.TREBLE_UP, R.drawable.ic_up, R.drawable.key_round), round, round))
        body.addView(ups, fullWidth().apply { topMargin = dp(14) })

        body.addView(textKey(Keys.EQ, "EQ"), LinearLayout.LayoutParams(dp(76), dp(42)))

        val downs = row()
        downs.addView(cell(iconKey(Keys.BASS_DOWN, R.drawable.ic_down, R.drawable.key_round), round, round))
        downs.addView(cell(iconKey(Keys.TREBLE_DOWN, R.drawable.ic_down, R.drawable.key_round), round, round))
        body.addView(downs, fullWidth())

        val names = row()
        names.addView(cell(caption("BASS"), -2, -2))
        names.addView(cell(caption("TREBLE"), -2, -2))
        body.addView(names, fullWidth().apply { topMargin = dp(4) })

        body.addView(label("aiwa", 26f).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, fullWidth().apply { topMargin = dp(12) })
        body.addView(label("ASB-060", 12f, muted = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
        }, fullWidth())

        val bodyWidth = minOf(resources.displayMetrics.widthPixels - dp(32), dp(320))
        root.addView(body, LinearLayout.LayoutParams(bodyWidth, -2).apply {
            topMargin = dp(10)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        return ScrollView(this).apply { addView(root); isFillViewport = true }
    }

    private fun caption(text: String) = label(text, 11f, muted = true).apply {
        letterSpacing = 0.15f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun topButton(text: String, onClick: (View) -> Unit) = textButton(text, size = 14f).apply {
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(6) }
    }

    private fun iconKey(key: RemoteKey, icon: Int, background: Int): View =
        bind(iconButton(icon, background, key.label), key)

    private fun textKey(key: RemoteKey, text: String): View =
        bind(textButton(text, R.drawable.key_pill, 14f).apply { letterSpacing = 0.08f }, key)

    @Suppress("ClickableViewAccessibility")
    private fun bind(view: View, key: RemoteKey): View {
        keyViews[key.id] = view
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    // Le défilement de l'écran ne doit pas interrompre une touche maintenue.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    val code = store.get(key)
                    if (!editMode && code != null) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        // Comme la vraie télécommande : le code se répète tant que le doigt reste posé.
                        ir.startHold(code)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    ir.stopHold()
                    if (editMode) editKey(key)
                    else if (store.get(key) == null) {
                        Toast.makeText(this, "« ${keyName(key.id)} » n'a pas encore de code : lancez la Recherche.", Toast.LENGTH_SHORT).show()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    ir.stopHold()
                }
            }
            true
        }
        return view
    }

    private fun refresh() {
        val n = store.configuredCount
        status.text = when {
            !ir.available -> "Émetteur infrarouge introuvable"
            editMode -> "Touchez une touche pour voir ou changer son code"
            else -> "$n touches sur ${Keys.all.size} réglées"
        }
        for (k in Keys.all) keyViews[k.id]?.alpha = if (editMode || store.get(k) != null) 1f else 0.35f
        editBtn.text = if (editMode) "Terminé" else "Éditer"

        bannerButtons.removeAllViews()
        when {
            editMode -> banner.visibility = View.GONE
            n == 0 -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "Aucune touche n'a de code pour l'instant."
                db.twin?.let { twin -> bannerButtons.addView(bannerButton("Codes probables") { loadProfile(twin) }) }
                bannerButtons.addView(bannerButton("Recherche automatique", primary = true) { openSearch() })
            }
            !store.verified -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "Codes probables chargés" + (store.profileName?.let { " ($it)" } ?: "") +
                    ", pas encore vérifiés. Visez la barre avec le haut du téléphone et appuyez plusieurs fois " +
                    "sur VOL +, ou sur Power."
                bannerButtons.addView(bannerButton("Ça ne marche pas") { notWorking() })
                bannerButtons.addView(bannerButton("✔ Ça marche", primary = true) {
                    store.verified = true
                    refresh()
                    Toast.makeText(this, "Parfait ! La télécommande est prête.", Toast.LENGTH_SHORT).show()
                })
            }
            else -> banner.visibility = View.GONE
        }
    }

    private fun bannerButton(text: String, primary: Boolean = false, onClick: () -> Unit) =
        textButton(text, if (primary) R.drawable.key_accent else R.drawable.key_bg, 13f).apply {
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { leftMargin = dp(8) }
        }

    private fun notWorking() {
        AlertDialog.Builder(this)
            .setTitle("Rien ne réagit ?")
            .setMessage(
                "Vérifiez d'abord :\n" +
                    "• la barre de son est allumée ;\n" +
                    "• le haut du téléphone pointe vers l'avant de la barre, à 1 à 3 mètres ;\n" +
                    "• rien ne cache la barre.\n\n" +
                    "Pour voir si le téléphone émet bien : regardez son bord supérieur à travers l'appareil photo " +
                    "d'un autre téléphone en appuyant sur une touche. Une petite lumière violette doit clignoter.\n\n" +
                    "Si rien ne se passe toujours, lancez la recherche automatique : l'appli essaie les codes des " +
                    "télécommandes proches, puis tous les autres, et s'arrête toute seule quand la barre réagit."
            )
            .setPositiveButton("Recherche automatique") { _, _ -> openSearch() }
            .setNegativeButton("Réessayer", null).show()
    }

    private fun openSearch() {
        if (editMode) toggleEdit()
        startActivity(Intent(this, ScannerActivity::class.java))
    }

    private fun toggleEdit() {
        editMode = !editMode
        refresh()
    }

    private fun loadProfile(profile: Profile) {
        val n = store.loadProfile(profile, verified = false)
        refresh()
        Toast.makeText(this, "$n touches chargées (${profile.name}). Essayez-les !", Toast.LENGTH_LONG).show()
    }

    private fun chooseProfile() {
        val list = db.profiles
        if (list.isEmpty()) return
        val items = list.map { "${it.name}\n${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Télécommandes proches")
            .setItems(items) { _, i -> loadProfile(list[i]) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun editKey(key: RemoteKey) {
        val input = EditText(this).apply {
            setText(store.get(key)?.format() ?: "NEC:20:00")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 4
            setSelectAllOnFocus(true)
        }
        val box = column().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(label("Formats : NEC:AA:CC (hexadécimal), NEC:AAAA:CC (adresse 16 bits), code Pronto « 0000 006D … » ou RAW:38000:9000,4500,…", 12f, muted = true))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(key.label)
            .setView(box)
            .setPositiveButton("Enregistrer", null)
            .setNeutralButton("Tester", null)
            .setNegativeButton("Effacer") { _, _ -> store.set(key, null); refresh() }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = IrCode.parse(input.text.toString())
                if (c == null) input.error = "Code invalide" else {
                    store.set(key, c); refresh(); dlg.dismiss()
                }
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
        m.menu.add(0, 1, 0, "Recherche automatique")
        m.menu.add(0, 2, 1, "Choisir une télécommande proche…")
        m.menu.add(0, 3, 2, "Exporter les codes (presse-papiers)")
        m.menu.add(0, 4, 3, "Importer les codes (presse-papiers)")
        m.menu.add(0, 5, 4, "Tout effacer")
        m.menu.add(0, 6, 5, "Aide")
        m.setOnMenuItemClickListener { item ->
            val cb = getSystemService(ClipboardManager::class.java)
            when (item.itemId) {
                1 -> openSearch()
                2 -> chooseProfile()
                3 -> {
                    cb.setPrimaryClip(ClipData.newPlainText("codes", store.exportJson()))
                    Toast.makeText(this, "Codes copiés dans le presse-papiers", Toast.LENGTH_SHORT).show()
                }
                4 -> {
                    val txt = cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                    val n = try {
                        store.importJson(txt)
                    } catch (e: Exception) {
                        -1
                    }
                    Toast.makeText(this, if (n < 0) "Presse-papiers invalide" else "$n touche(s) importée(s)", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                5 -> AlertDialog.Builder(this).setMessage("Effacer les codes de toutes les touches ?")
                    .setPositiveButton("Effacer") { _, _ -> store.clearAll(); refresh() }
                    .setNegativeButton("Annuler", null).show()
                6 -> showHelp()
            }
            true
        }
        m.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Comment ça marche")
            .setMessage(
                "Le Poco X5 5G a un émetteur infrarouge sur son bord supérieur : visez la barre de son avec le haut " +
                    "du téléphone.\n\n" +
                    "Les touches sont celles de la télécommande d'origine. Comme sur celle-ci, garder le doigt sur une " +
                    "touche répète le code (volume, basses, aigus). Sur la barre jumelle, garder EQ appuyé allume ou " +
                    "éteint la lumière.\n\n" +
                    "Les codes chargés au départ sont ceux d'une barre de son qui a exactement la même télécommande " +
                    "(Majority K2). S'ils ne marchent pas, la Recherche essaie les télécommandes proches, puis tous " +
                    "les codes, du plus probable au moins probable.\n\n" +
                    "Pour que la recherche s'arrête toute seule : connectez le téléphone à la barre en Bluetooth et " +
                    "lancez une musique depuis le téléphone avant de la démarrer.\n\n" +
                    "Éditer permet de voir ou de saisir un code à la main."
            )
            .setPositiveButton("OK", null).show()
    }
}
