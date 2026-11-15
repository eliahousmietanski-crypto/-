package com.aiwa.asb060

import android.content.Context
import org.json.JSONObject

data class RemoteKey(val id: String, val label: String, val repeat: Boolean = false)

/** Touches de la télécommande d'origine de la barre de son. */
object Keys {
    val POWER = RemoteKey("power", "⏻")
    val MUTE = RemoteKey("mute", "🔇 Muet")

    /** Disposition : chaque ligne est une rangée de touches (null = espace vide). */
    val layout: List<List<RemoteKey?>> = listOf(
        listOf(RemoteKey("source", "Source"), RemoteKey("bt", "Bluetooth"), RemoteKey("optical", "Optique")),
        listOf(RemoteKey("aux", "AUX"), RemoteKey("hdmi", "HDMI ARC"), RemoteKey("usb", "USB")),
        listOf(RemoteKey("prev", "⏮"), RemoteKey("play", "⏯"), RemoteKey("next", "⏭")),
        listOf(RemoteKey("voldown", "VOL −", true), RemoteKey("volup", "VOL +", true)),
        listOf(RemoteKey("bassdown", "Basses −", true), RemoteKey("bassup", "Basses +", true)),
        listOf(RemoteKey("trebledown", "Aigus −", true), RemoteKey("trebleup", "Aigus +", true)),
        listOf(RemoteKey("music", "Musique"), RemoteKey("movie", "Film"), RemoteKey("news", "Voix")),
        listOf(RemoteKey("eq", "EQ"), RemoteKey("dimmer", "Lumière"), RemoteKey("reset", "Reset")),
    )

    val all: List<RemoteKey> = listOf(POWER, MUTE) + layout.flatten().filterNotNull()
}

/** Sauvegarde des codes associés à chaque touche. */
class CodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("codes", Context.MODE_PRIVATE)

    fun get(key: RemoteKey): IrCode? = prefs.getString(key.id, null)?.let { IrCode.parse(it) }

    fun set(key: RemoteKey, code: IrCode?) {
        prefs.edit().apply {
            if (code == null) remove(key.id) else putString(key.id, code.format())
        }.apply()
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
            val v = o.optString(k.id, "")
            if (v.isNotEmpty() && IrCode.parse(v) != null) { e.putString(k.id, v); n++ }
        }
        e.apply()
        return n
    }

    fun clearAll() = prefs.edit().clear().apply()
}
