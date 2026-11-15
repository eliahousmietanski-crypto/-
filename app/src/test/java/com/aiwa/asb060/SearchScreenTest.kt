package com.aiwa.asb060

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchScreenTest {
    private val bar = FakeBar()
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<ScannerActivity>
    private val root: View get() = controller.get().window.decorView

    @Before
    fun setUp() {
        Deps.sender = { bar }
        Deps.probe = { bar }
        Deps.clock = { bar.now }
    }

    @After
    fun tearDown() {
        Deps.sender = { IrTransmitter(it) }
        Deps.probe = { AudioProbe(it) }
    }

    private fun launch(): ScannerActivity {
        controller = Robolectric.buildActivity(ScannerActivity::class.java).setup()
        idle()
        return controller.get()
    }

    /** Laisse tourner l'écran [ms] millisecondes, la fausse barre réagissant en même temps. */
    private fun run(ms: Long) {
        repeat((ms / 100).toInt()) {
            bar.step()
            idleFor(100)
        }
    }

    private fun start() = click(allViews(root).filterIsInstance<TextView>().first { it.text.toString().startsWith("▶") })

    @Test
    fun screenExplainsWhatToDo() {
        launch()
        val text = textOf(root)
        assertTrue(text, text.contains("Étape 1 sur 3 · Télécommandes de barres de son proches"))
        assertTrue(text, text.contains("Prochain essai : Majority K2 SoundBar · Lecture / Pause"))
        assertTrue(text, text.contains("NEC:20:1B"))
        assertTrue(text, text.contains("● Musique en lecture"))
        assertTrue(text, text.contains("▶  Lancer la recherche"))
        shot(root, "search_start")

        bar.music = false
        run(1500)
        assertTrue(textOf(root).contains("Lancez maintenant une musique"))
        bar.bt = false
        run(1500)
        assertTrue(textOf(root).contains("Barre non connectée en Bluetooth"))
        shot(root, "search_no_bluetooth")
    }

    @Test
    fun twinCodeIsFoundAndTheWholeRemoteIsLoaded() {
        val a = launch()
        bar.codes["NEC:20:1B"] = "play"
        start()
        run(1200)
        assertTrue(textOf(root).split("\n").contains("Pause"))
        run(1500)
        shot(root, "search_checking")
        run(8000)

        val d = latestDialog()
        assertEquals("Code trouvé !", dialogTitle(d))
        val text = dialogText(d)
        assertTrue(text, text.contains("NEC:20:1B met la musique en pause"))
        assertTrue(text, text.contains("vérifié"))
        assertTrue(text, text.contains("« Lecture / Pause » de la télécommande « Majority K2 SoundBar »"))
        shot(d.window!!.decorView, "search_found_dialog")
        assertTrue("la musique continue", bar.music)

        click(byText(d.window!!.decorView, "Charger les 12 touches"))
        val store = CodeStore(app)
        assertEquals(12, store.configuredCount)
        assertEquals("Majority K2 SoundBar", store.profileName)
        assertTrue(store.verified)
        assertEquals("NEC:20:42", store.get(Keys.VOL_UP)?.format())
        assertTrue(a.isFinishing)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("12 touches chargées"))
    }

    @Test
    fun runningSearchShowsProgressAndHistory() {
        launch()
        start()
        run(5000)
        val text = textOf(root)
        assertTrue(text, text.contains("Essai 7 sur 96 de cette étape"))
        assertTrue(text, text.split("\n").contains("Pause"))
        assertEquals(7, bar.sent.size)
        assertTrue(text, text.contains(bar.sent.last()) || text.contains("Code brut"))
        shot(root, "search_running")
        click(byText(root, "Pause"))
        run(3000)
        assertEquals(7, bar.sent.size)
        assertTrue(textOf(root).contains("▶  Reprendre la recherche"))
    }

    @Test
    fun powerCodeAsksTheUserThenLoadsTheRemote() {
        val a = launch()
        val plan = ScanPlan.full(ProfileDb.get(app))
        val index = (0 until plan.phaseStarts[1]).first { plan[it].function == "power" }
        val target = plan[index].code
        bar.codes[target.format()] = "power"
        start()
        run((index + 8) * 800L)

        val d = latestDialog()
        assertEquals("La barre a réagi : Bluetooth coupé", dialogTitle(d))
        val box = d.window!!.decorView
        assertTrue(dialogText(d), dialogText(d).contains(target.short()))
        shot(box, "search_bluetooth_lost_dialog")
        assertFalse(bar.music)

        // « Tester » renvoie le code : la barre se rallume.
        val rows = allViews(box).filterIsInstance<TextView>()
        val line = rows.first { it.text.toString() == target.short() }.parent.parent as View
        val sentBefore = bar.sent.size
        click(byText(line, "Tester"))
        assertEquals(target.format(), bar.sent.last())
        assertEquals(sentBefore + 1, bar.sent.size)
        bar.step(); bar.step(); bar.step(); bar.step(); bar.step()
        assertTrue(bar.barOn)

        click(byText(line, "C'est lui"))
        val found = latestDialog()
        assertEquals("Code trouvé !", dialogTitle(found))
        assertTrue(dialogText(found), dialogText(found).contains("« Power » de la télécommande « ${plan[index].profile!!.name} »"))
        click(allViews(found.window!!.decorView).filterIsInstance<TextView>().first { it.text.startsWith("Charger les") })
        assertEquals(plan[index].profile!!.name, CodeStore(app).profileName)
        assertFalse("codes pas encore vérifiés automatiquement", CodeStore(app).verified)
        assertEquals(target.format(), CodeStore(app).get(Keys.POWER)?.format())
        assertTrue(a.isFinishing)
    }

    @Test
    fun unknownCodeIsStoredOnTheRightKey() {
        launch()
        bar.codes["NEC:20:99"] = "play"

        // Réglages avancés : chercher sur l'adresse 20 seulement.
        click(byText(root, "Réglages avancés"))
        shot(root, "search_advanced")
        click(byText(root, "Une seule adresse…"))
        val ask = latestDialog()
        allViews(ask.window!!.decorView).filterIsInstance<EditText>().single().setText("20")
        click(ask.getButton(AlertDialog.BUTTON_POSITIVE))
        assertTrue(textOf(root), textOf(root).contains("Étape 1 sur 1 · Toutes les commandes de l'adresse 20"))
        assertTrue(textOf(root).contains("Recherche limitée à l'adresse 20."))

        start()
        run(0x99 * 800L + 12_000)
        val pick = latestDialog()
        assertTrue(dialogText(pick), dialogText(pick).contains("Sur quelle touche ranger NEC:20:99 ?"))
        assertTrue(dialogText(pick), dialogText(pick).contains("NEC:20:99 met la musique en pause (vérifié"))
        val list = allViews(pick.window!!.decorView).filterIsInstance<ListView>().single()
        assertEquals(Keys.all.indexOf(Keys.PLAY), list.checkedItemPosition)
        shot(pick.window!!.decorView, "search_pick_key_dialog")
        click(pick.getButton(AlertDialog.BUTTON_POSITIVE))

        val store = CodeStore(app)
        assertEquals("NEC:20:99", store.get(Keys.PLAY)?.format())
        assertNull(store.profileName)
        assertTrue(store.verified)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("NEC:20:99 → Lecture / Pause"))
    }

    @Test
    fun sameRemoteOnAnotherAddressIsLoadedWhole() {
        val a = launch()
        // Barre qui a la télécommande identique, mais réglée sur l'adresse 35.
        bar.codes["NEC:35:1B"] = "play"
        val plan = ScanPlan.full(ProfileDb.get(app))
        val index = (plan.phaseStarts[1] until plan.phaseStarts[2]).first { plan[it].code.format() == "NEC:35:1B" }
        click(byText(root, "Réglages avancés"))
        click(byText(root, "Étape 2"))
        start()
        run((index - plan.phaseStarts[1] + 14) * 800L)

        val found = latestDialog()
        assertEquals("Code trouvé !", dialogTitle(found))
        val text = dialogText(found)
        assertTrue(text, text.contains("NEC:35:1B met la musique en pause"))
        assertTrue(text, text.contains("la télécommande identique à la vôtre, mais réglée sur l'adresse 35"))
        shot(found.window!!.decorView, "search_other_address_dialog")
        click(allViews(found.window!!.decorView).filterIsInstance<TextView>().last { it.text.startsWith("Charger les") })

        val store = CodeStore(app)
        assertEquals(12, store.configuredCount)
        assertEquals("NEC:35:42", store.get(Keys.VOL_UP)?.format())
        assertEquals("NEC:35:41", store.get(Keys.POWER)?.format())
        assertEquals("NEC:35:5A", store.get(Keys.EQ)?.format())
        assertEquals("Majority K2 SoundBar · adresse 35", store.profileName)
        assertTrue(store.verified)
        assertTrue(a.isFinishing)
    }

    @Test
    fun manualReportListsTheLastCodes() {
        launch()
        click(byText(root, "✔ La barre a réagi !"))
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Aucun code n'a encore été envoyé"))

        click(byText(root, "Suivant ▶"))
        click(byText(root, "Suivant ▶"))
        click(byText(root, "Suivant ▶"))
        click(byText(root, "◀ Précédent"))
        click(byText(root, "Renvoyer"))
        val plan = ScanPlan.full(ProfileDb.get(app))
        assertEquals(listOf(0, 1, 2, 1, 1).map { plan[it].code.format() }, bar.sent)
        assertTrue(textOf(root).contains("Essai 2 sur"))

        click(byText(root, "✔ La barre a réagi !"))
        val d = latestDialog()
        assertEquals("Quel code a fait réagir la barre ?", dialogTitle(d))
        val text = dialogText(d)
        for (i in 0..2) assertTrue(text, text.contains(plan[i].code.short()))
        shot(d.window!!.decorView, "search_manual_dialog")

        // « C'est lui » sur le code de la télécommande identique propose de tout charger.
        val line = allViews(d.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == "NEC:20:1B" }.parent.parent as View
        click(byText(line, "C'est lui"))
        val found = latestDialog()
        assertEquals("Code trouvé !", dialogTitle(found))
        click(found.getButton(AlertDialog.BUTTON_NEGATIVE))
        val pick = latestDialog()
        assertTrue(dialogText(pick), dialogText(pick).contains("Sur quelle touche ranger NEC:20:1B ?"))
        click(pick.getButton(AlertDialog.BUTTON_POSITIVE))
        assertEquals("NEC:20:1B", CodeStore(app).get(Keys.PLAY)?.format())
        assertEquals(1, CodeStore(app).configuredCount)
        // Puis propose de chercher les autres touches sur la même adresse.
        val more = latestDialog()
        assertEquals("Chercher les autres touches ?", dialogTitle(more))
        click(more.getButton(AlertDialog.BUTTON_POSITIVE))
        assertTrue(textOf(root).contains("Toutes les commandes de l'adresse 20"))
    }

    @Test
    fun positionSurvivesLeavingTheScreen() {
        launch()
        start()
        run(6000)
        val sent = bar.sent.size
        assertTrue(sent >= 7)
        controller.pause().stop().destroy()
        assertEquals(sent, app.getSharedPreferences("search", Context.MODE_PRIVATE).getInt("index", -1))

        launch()
        val text = textOf(root)
        assertTrue(text, text.contains("Essai ${sent + 1} sur"))
        assertTrue(text, text.contains("▶  Reprendre la recherche"))
        assertTrue(text, text.contains("Prochain essai :"))
        start()
        run(1000)
        val plan = ScanPlan.full(ProfileDb.get(app))
        assertEquals(plan[sent].code.format(), bar.sent[sent])
    }

    @Test
    fun leavingTheScreenStopsTheSearch() {
        launch()
        start()
        run(3000)
        controller.pause()
        val sent = bar.sent.size
        run(5000)
        assertEquals(sent, bar.sent.size)
    }

    @Test
    fun jumpingToAStepAndChangingTheInterval() {
        launch()
        click(byText(root, "Réglages avancés"))
        click(byText(root, "Étape 2"))
        assertTrue(textOf(root), textOf(root).contains("Étape 2 sur 3 · Même télécommande, réglée autrement"))
        click(byText(root, "Étape 3"))
        assertTrue(textOf(root), textOf(root).contains("Étape 3 sur 3 · Tous les codes"))
        assertTrue(textOf(root), textOf(root).contains("Prochain essai : Adresse 20 (1 sur"))
        assertTrue(textOf(root), textOf(root).contains("Essai 1 sur 256 pour cette adresse · encore environ 3 min"))

        val interval = allViews(root).filterIsInstance<EditText>().single()
        assertEquals("800", interval.text.toString())
        interval.setText("400")
        start()
        run(4000)
        assertEquals(10, bar.sent.size)
        assertTrue(bar.sent.all { it.startsWith("NEC:20:") })
        // Une valeur trop petite est ramenée au minimum.
        interval.setText("5")
        run(3000)
        assertEquals(20, bar.sent.size)
    }
}
