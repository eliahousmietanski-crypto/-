package com.aiwa.asb060

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.EditText
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
class RemoteScreenTest {
    private val bar = FakeBar()
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<MainActivity>

    private val twinCodes = mapOf(
        Keys.POWER to "NEC:20:41", Keys.MODE to "NEC:20:50", Keys.VOL_UP to "NEC:20:42", Keys.VOL_DOWN to "NEC:20:43",
        Keys.PREV to "NEC:20:1A", Keys.PLAY to "NEC:20:1B", Keys.NEXT to "NEC:20:4F", Keys.BASS_UP to "NEC:20:14",
        Keys.BASS_DOWN to "NEC:20:56", Keys.TREBLE_UP to "NEC:20:4D", Keys.TREBLE_DOWN to "NEC:20:51", Keys.EQ to "NEC:20:5A",
    )

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

    private fun launch(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        return controller.get()
    }

    private fun keyView(a: MainActivity, key: RemoteKey): View {
        val root = a.window.decorView
        return when (key) {
            Keys.MODE -> byText(root, "MODE")
            Keys.EQ -> byText(root, "EQ")
            else -> byDescription(root, key.label)
        }
    }

    @Test
    fun firstLaunchLoadsTheTwinRemote() {
        val a = launch()
        val welcome = latestDialog()
        assertEquals("Codes probables chargés", dialogTitle(welcome))
        assertTrue(dialogText(welcome).contains("Majority K2 SoundBar"))
        shot(welcome.window!!.decorView, "main_welcome_dialog")
        click(welcome.getButton(AlertDialog.BUTTON_POSITIVE))

        val store = CodeStore(app)
        assertEquals(12, store.configuredCount)
        assertEquals("Majority K2 SoundBar", store.profileName)
        assertFalse(store.verified)
        val text = textOf(a.window.decorView)
        assertTrue(text, text.contains("12 touches sur 12 réglées"))
        assertTrue(text, text.contains("Codes probables chargés (Majority K2 SoundBar)"))
        assertTrue(text, text.contains("pas encore vérifiés"))
        shot(a.window.decorView, "main_first_launch")

        // Chaque touche envoie le code attendu, puis s'arrête quand on lève le doigt.
        for ((key, code) in twinCodes) {
            bar.held.clear()
            val stops = bar.stops
            tap(keyView(a, key))
            assertEquals(key.label, listOf(code), bar.held)
            assertTrue(key.label, bar.stops > stops)
        }
        assertEquals(12, bar.holds)
    }

    @Test
    fun secondLaunchDoesNotAskAgain() {
        launch().finish()
        click(latestDialog().getButton(AlertDialog.BUTTON_POSITIVE))
        val before = org.robolectric.shadows.ShadowDialog.getShownDialogs().size
        launch()
        assertEquals(before, org.robolectric.shadows.ShadowDialog.getShownDialogs().size)
        assertEquals(12, CodeStore(app).configuredCount)
    }

    @Test
    fun confirmingHidesTheBanner() {
        val a = launch()
        click(latestDialog().getButton(AlertDialog.BUTTON_POSITIVE))
        click(byText(a.window.decorView, "✔ Ça marche"))
        assertTrue(CodeStore(app).verified)
        val text = textOf(a.window.decorView)
        assertFalse(text, text.contains("pas encore vérifiés"))
        assertFalse(text, text.contains("Ça ne marche pas"))
        shot(a.window.decorView, "main_verified")
    }

    @Test
    fun notWorkingLeadsToTheSearch() {
        val a = launch()
        click(latestDialog().getButton(AlertDialog.BUTTON_POSITIVE))
        click(byText(a.window.decorView, "Ça ne marche pas"))
        val d = latestDialog()
        assertEquals("Rien ne réagit ?", dialogTitle(d))
        click(d.getButton(AlertDialog.BUTTON_POSITIVE))
        val next = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        assertEquals(ScannerActivity::class.java.name, next.component?.className)
    }

    @Test
    fun existingCodesAreKeptUnlessTheUserAccepts() {
        CodeStore(app).set(Keys.VOL_UP, IrCode.parse("NEC:01:02"))
        val a = launch()
        val d = latestDialog()
        assertEquals("Codes probables trouvés", dialogTitle(d))
        assertEquals("NEC:01:02", CodeStore(app).get(Keys.VOL_UP)?.format())
        assertEquals(1, CodeStore(app).configuredCount)
        click(d.getButton(AlertDialog.BUTTON_POSITIVE))
        assertEquals(12, CodeStore(app).configuredCount)
        assertEquals("NEC:20:42", CodeStore(app).get(Keys.VOL_UP)?.format())
        assertTrue(textOf(a.window.decorView).contains("pas encore vérifiés"))
    }

    @Test
    fun keyWithoutCodeSendsNothing() {
        val a = launch()
        click(latestDialog().getButton(AlertDialog.BUTTON_POSITIVE))
        CodeStore(app).clearAll()
        // Retour sur l'écran, comme après un passage par un autre écran.
        controller.pause().resume()
        idle()
        tap(keyView(a, Keys.VOL_UP))
        assertTrue(bar.held.isEmpty())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("n'a pas encore de code"))
        val text = textOf(a.window.decorView)
        assertTrue(text, text.contains("Aucune touche n'a de code"))
        shot(a.window.decorView, "main_empty")
        // « Codes probables » recharge la télécommande identique.
        click(byText(a.window.decorView, "Codes probables"))
        assertEquals(12, CodeStore(app).configuredCount)
    }

    @Test
    fun editModeChangesACode() {
        val a = launch()
        click(latestDialog().getButton(AlertDialog.BUTTON_POSITIVE))
        click(byText(a.window.decorView, "Éditer"))
        shot(a.window.decorView, "main_edit")
        tap(keyView(a, Keys.EQ))
        assertTrue("rien n'est envoyé en mode édition", bar.held.isEmpty())
        val d = latestDialog()
        assertEquals(Keys.EQ.label, dialogTitle(d))
        val input = allViews(d.window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals("NEC:20:5A", input.text.toString())

        input.setText("n'importe quoi")
        click(d.getButton(AlertDialog.BUTTON_POSITIVE))
        assertTrue(d.isShowing)
        assertEquals("NEC:20:5A", CodeStore(app).get(Keys.EQ)?.format())

        input.setText("nec:20:99")
        click(d.getButton(AlertDialog.BUTTON_NEUTRAL))
        assertEquals("NEC:20:99", bar.sent.last())
        click(d.getButton(AlertDialog.BUTTON_POSITIVE))
        assertFalse(d.isShowing)
        assertEquals("NEC:20:99", CodeStore(app).get(Keys.EQ)?.format())

        click(byText(a.window.decorView, "Terminé"))
        tap(keyView(a, Keys.EQ))
        assertEquals(listOf("NEC:20:99"), bar.held)
    }

    @Test
    fun oldSourceKeyAndExportImport() {
        app.getSharedPreferences("codes", Context.MODE_PRIVATE).edit().putString("source", "NEC:00:02").commit()
        val store = CodeStore(app)
        assertEquals("NEC:00:02", store.get(Keys.MODE)?.format())
        store.set(Keys.PLAY, IrCode.parse("0000 006D 0002 0000 0157 00AC 0015 0016"))
        val json = store.exportJson()
        store.clearAll()
        assertEquals(0, store.configuredCount)
        assertNull(store.get(Keys.MODE))
        assertEquals(2, store.importJson(json))
        assertEquals("NEC:00:02", store.get(Keys.MODE)?.format())
        assertTrue(store.get(Keys.PLAY) is IrCode.Raw)
        assertTrue(store.verified)
        // Export d'une ancienne version : la touche s'appelait « source ».
        store.clearAll()
        assertEquals(1, store.importJson("""{"source":"NEC:20:50","mute":"NEC:20:1B"}"""))
        assertEquals("NEC:20:50", store.get(Keys.MODE)?.format())
    }
}
