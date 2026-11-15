package com.aiwa.asb060

import android.content.Context

/**
 * Une télécommande connue (barre de son ou enceinte d'une autre marque), avec ses codes
 * rangés sur les touches de l'ASB-060.
 */
class Profile(
    val name: String,
    val category: String,
    /** Ressemblance avec la télécommande de l'ASB-060, de 0 à 100. */
    val score: Int,
    /** Nombre de touches de l'ASB-060 que cette télécommande possède aussi. */
    val matched: Int,
    val total: Int,
    /** Vrai si la télécommande est physiquement identique à celle de l'ASB-060. */
    val twin: Boolean,
    val codes: Map<String, IrCode>,
) {
    /** Code à mettre sur [key] ; la touche centrale sert aussi de « muet ». */
    fun codeFor(key: RemoteKey): IrCode? =
        codes[key.id] ?: if (key == Keys.PLAY) codes["mute"] else null

    val keyCount: Int get() = Keys.all.count { codeFor(it) != null }

    val description: String
        get() = if (twin) "télécommande identique · $keyCount touches"
        else "ressemblance $score % · $keyCount touches"
}

/** Adresse NEC rencontrée sur des appareils audio, avec les commandes connues pour elle. */
class KnownAddress(val address: String, val count: Int, val commands: List<Int>)

/**
 * Base des télécommandes proches de celle de l'ASB-060, classées par ressemblance.
 * Générée par tools/build_profiles.py à partir de Flipper-IRDB (CC0).
 */
class ProfileDb(val profiles: List<Profile>, val addresses: List<KnownAddress>) {

    /** La télécommande identique à celle de l'ASB-060, si elle est dans la base. */
    val twin: Profile? = profiles.firstOrNull { it.twin }

    /** Les télécommandes connues qui contiennent exactement ce code, et la touche concernée. */
    fun matches(code: IrCode): List<Pair<Profile, String>> {
        val out = ArrayList<Pair<Profile, String>>()
        for (p in profiles) {
            val hit = p.codes.entries.firstOrNull { it.value == code } ?: continue
            out += Pair(p, hit.key)
        }
        return out
    }

    companion object {
        @Volatile
        private var cached: ProfileDb? = null

        fun get(context: Context): ProfileDb = cached ?: synchronized(this) {
            cached ?: try {
                context.assets.open("profiles.txt").bufferedReader(Charsets.UTF_8).useLines { parse(it) }
            } catch (e: Exception) {
                ProfileDb(emptyList(), emptyList())
            }.also { cached = it }
        }

        fun parse(lines: Sequence<String>): ProfileDb {
            val profiles = ArrayList<Profile>()
            val addresses = ArrayList<KnownAddress>()
            var header: List<String>? = null
            var codes = LinkedHashMap<String, IrCode>()

            fun flush() {
                val h = header ?: return
                if (codes.isNotEmpty()) {
                    profiles += Profile(
                        name = h[2],
                        category = h[1],
                        score = h.getOrNull(3)?.toIntOrNull() ?: 0,
                        matched = h.getOrNull(4)?.toIntOrNull() ?: 0,
                        total = h.getOrNull(5)?.toIntOrNull() ?: 0,
                        twin = h.getOrNull(6) == "T",
                        codes = codes,
                    )
                }
                header = null
                codes = LinkedHashMap()
            }

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when (line[0]) {
                    'P' -> {
                        flush()
                        val f = line.split("|")
                        if (f.size >= 3) header = f
                    }
                    'K' -> {
                        val f = line.split("|", limit = 3)
                        if (f.size == 3 && header != null) IrCode.parse(f[2])?.let { codes[f[1]] = it }
                    }
                    'A' -> {
                        flush()
                        val f = line.split("|")
                        if (f.size >= 4 && IrCode.Nec.of(f[1], 0) != null) {
                            val cmds = f[3].split(",").mapNotNull { it.trim().toIntOrNull(16) }.filter { it in 0..0xFF }
                            addresses += KnownAddress(f[1].uppercase(), f[2].toIntOrNull() ?: 0, cmds.distinct())
                        }
                    }
                }
            }
            flush()
            return ProfileDb(profiles.sortedWith(compareByDescending<Profile> { it.twin }.thenByDescending { it.score }), addresses)
        }
    }
}
