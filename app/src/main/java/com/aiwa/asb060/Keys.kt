package com.aiwa.asb060

import android.content.Context
import org.json.JSONObject

data class RemoteKey(val id: String, val label: String)

/** Les 12 touches de la télécommande d'origine de l'Aiwa ASB-060. */
object Keys {
    val POWER = RemoteKey("power", "Power (marche / arrêt)")
    val MODE = RemoteKey("mode", "MODE (changer de source)")
    val VOL_UP = RemoteKey("volup", "Volume +")
    val VOL_DOWN = RemoteKey("voldown", "Volume −")
    val PREV = RemoteKey("prev", "Piste précédente")
    val PLAY = RemoteKey("play", "Lecture / Pause")
    val NEXT = RemoteKey("next", "Piste suivante")
    val BASS_UP = RemoteKey("bassup", "Basses +")
    val BASS_DOWN = RemoteKey("bassdown", "Basses −")
    val TREBLE_UP = RemoteKey("trebleup", "Aigus +")
    val TREBLE_DOWN = RemoteKey("trebledown", "Aigus −")
    val EQ = RemoteKey("eq", "EQ (mode sonore)")

    val all: List<RemoteKey> = listOf(
        POWER, MODE, VOL_UP, VOL_DOWN, PREV, PLAY, NEXT,
        BASS_UP, BASS_DOWN, TREBLE_UP, TREBLE_DOWN, EQ,
    )

    fun byId(id: String?): RemoteKey? = all.firstOrNull { it.id == id }
}

/** Sauvegarde des codes associés à chaque touche. */
class CodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("codes", Context.MODE_PRIVATE)

    init {
        // Les premières versions appelaient la touche MODE « source ».
        val old = prefs.getString("source", null)
        if (old != null && !prefs.contains(Keys.MODE.id)) {
            prefs.edit().putString(Keys.MODE.id, old).remove("source").apply()
        }
    }

    fun get(key: RemoteKey): IrCode? = prefs.getString(key.id, null)?.let { IrCode.parse(it) }

    fun set(key: RemoteKey, code: IrCode?) {
        prefs.edit().apply {
            if (code == null) remove(key.id) else putString(key.id, code.format())
        }.apply()
    }

    val configuredCount: Int get() = Keys.all.count { get(it) != null }

    /** Nom de la télécommande dont viennent les codes, s'ils ont été chargés d'un bloc. */
    var profileName: String?
        get() = prefs.getString(PROFILE, null)
        set(v) = prefs.edit().putString(PROFILE, v).apply()

    /** Vrai quand l'utilisateur (ou la recherche automatique) a confirmé que les codes marchent. */
    var verified: Boolean
        get() = prefs.getBoolean(VERIFIED, false)
        set(v) = prefs.edit().putBoolean(VERIFIED, v).apply()

    /** Vrai une fois que les codes probables ont été proposés au premier lancement. */
    var welcomed: Boolean
        get() = prefs.getBoolean(WELCOMED, false)
        set(v) = prefs.edit().putBoolean(WELCOMED, v).apply()

    /** Remplace toutes les touches par celles d'une télécommande connue. @return touches chargées */
    fun loadProfile(profile: Profile, verified: Boolean): Int {
        val e = prefs.edit()
        var n = 0
        for (k in Keys.all) {
            val code = profile.codeFor(k)
            if (code == null) e.remove(k.id) else {
                e.putString(k.id, code.format()); n++
            }
        }
        e.putString(PROFILE, profile.name).putBoolean(VERIFIED, verified).apply()
        return n
    }

    fun exportJson(): String {
        val o = JSONObject()
        o.put("device", "Aiwa ASB-060")
        for (k in Keys.all) prefs.getString(k.id, null)?.let { o.put(k.id, it) }
        return o.toString(2)
    }

    /** @return nombre de touches importées */
    fun importJson(json: String): Int {
        val o = JSONObject(json)
        var n = 0
        val e = prefs.edit()
        for (k in Keys.all) {
            val v = o.optString(k.id, "").ifEmpty { if (k == Keys.MODE) o.optString("source", "") else "" }
            val code = IrCode.parse(v) ?: continue
            e.putString(k.id, code.format()); n++
        }
        if (n > 0) e.remove(PROFILE).putBoolean(VERIFIED, true)
        e.apply()
        return n
    }

    fun clearAll() {
        val e = prefs.edit()
        for (k in Keys.all) e.remove(k.id)
        e.remove(PROFILE).remove(VERIFIED).apply()
    }

    private companion object {
        const val PROFILE = "_profile"
        const val VERIFIED = "_verified"
        const val WELCOMED = "_welcomed"
    }
}
