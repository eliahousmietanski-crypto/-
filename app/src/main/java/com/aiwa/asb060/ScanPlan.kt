package com.aiwa.asb060

import java.util.Locale

/** Un essai de la recherche : le code à envoyer et ce qu'on sait de lui. */
class Step(
    val code: IrCode,
    /** Numéro d'étape (à partir de 0). */
    val phase: Int,
    /** Texte affiché pendant l'essai. */
    val detail: String,
    /** Télécommande connue d'où vient ce code, s'il y en a une. */
    val profile: Profile? = null,
    /** Touche supposée (identifiant de [RemoteKey]), si elle est connue. */
    val function: String? = null,
)

/**
 * L'ordre des essais, du plus probable au moins probable.
 *
 * Recherche complète :
 *  1. la touche Lecture/Pause (à défaut Power) des télécommandes de barres de son proches,
 *     de la plus ressemblante à la moins ressemblante ;
 *  2. la touche Lecture/Pause de la télécommande identique, essayée sur toutes les adresses
 *     (même télécommande, mais réglée pour une autre marque) ;
 *  3. tous les codes, adresse par adresse, les adresses les plus courantes d'abord.
 */
class ScanPlan private constructor(
    val phaseTitles: List<String>,
    private val listed: List<Step>,
    private val sweepAddresses: List<String>,
    private val knownCommands: Map<String, List<Int>>,
    private val numericOrder: Boolean,
) {
    private val sweepPhase = phaseTitles.size - 1
    private val orderCache = HashMap<String, IntArray>()

    val size: Int = listed.size + sweepAddresses.size * 256

    /** Indice du premier essai de chaque étape. */
    val phaseStarts: List<Int> = run {
        val starts = ArrayList<Int>()
        var last = -1
        for ((i, s) in listed.withIndex()) if (s.phase != last) {
            starts += i; last = s.phase
        }
        if (sweepAddresses.isNotEmpty()) starts += listed.size
        starts
    }

    operator fun get(index: Int): Step {
        if (index < listed.size) return listed[index]
        val i = index - listed.size
        val address = sweepAddresses[i / 256]
        val command = commandOrder(address)[i % 256]
        val code = IrCode.Nec.of(address, command)!!
        val detail = String.format(
            Locale.US, "Adresse %s (%d sur %d) · commande %02X",
            address, i / 256 + 1, sweepAddresses.size, command,
        )
        return Step(code, sweepPhase, detail)
    }

    fun phaseOf(index: Int): Int = if (index < listed.size) listed[index].phase else sweepPhase

    /** Bornes (début inclus, fin exclue) de l'étape qui contient [index]. */
    fun phaseBounds(index: Int): IntRange {
        val k = phaseStarts.indexOfLast { it <= index }.coerceAtLeast(0)
        val end = if (k + 1 < phaseStarts.size) phaseStarts[k + 1] else size
        return phaseStarts[k] until end
    }

    /**
     * Bornes du groupe d'essais dont l'avancement est affiché : l'étape entière, ou, dans la
     * dernière étape, les 256 commandes de l'adresse en cours.
     */
    fun groupBounds(index: Int): IntRange {
        if (index < listed.size) return phaseBounds(index)
        val start = listed.size + (index - listed.size) / 256 * 256
        return start until start + 256
    }

    /** Vrai si [index] est dans le balayage adresse par adresse. */
    fun inSweep(index: Int): Boolean = index >= listed.size

    /** Commandes d'une adresse : celles déjà vues sur d'autres appareils d'abord, puis le reste. */
    private fun commandOrder(address: String): IntArray = orderCache.getOrPut(address) {
        if (numericOrder) return@getOrPut IntArray(256) { it }
        val known = knownCommands[address].orEmpty().filter { it in 0..0xFF }.distinct()
        (known + (0..0xFF).filter { it !in known }).toIntArray()
    }

    companion object {
        const val TITLE_SIMILAR = "Télécommandes de barres de son proches"
        const val TITLE_TWIN = "Même télécommande, réglée autrement"
        const val TITLE_ALL = "Tous les codes, les plus courants d'abord"

        fun full(db: ProfileDb): ScanPlan {
            val seen = HashSet<String>()
            val listed = ArrayList<Step>()
            val titles = ArrayList<String>()

            // Étape 1 : une touche « qui arrête la musique » par télécommande proche.
            // Les codes NEC passent avant les codes « bruts » (grandes marques, autre protocole),
            // bien moins probables pour cette barre.
            titles += TITLE_SIMILAR
            val similar = ArrayList<Step>()
            for (p in db.profiles) {
                val function = when {
                    p.codes.containsKey(Keys.PLAY.id) -> Keys.PLAY.id
                    p.codes.containsKey(Keys.POWER.id) -> Keys.POWER.id
                    else -> continue
                }
                val code = p.codes.getValue(function)
                if (!seen.add(code.format())) continue
                val label = Keys.byId(function)!!.label.substringBefore(" (")
                similar += Step(code, titles.size - 1, "${p.name} · $label", p, function)
            }
            listed += similar.sortedBy { it.code is IrCode.Raw }

            // Ordre des adresses : celle de la télécommande identique, puis les plus courantes
            // sur les appareils audio, puis toutes les autres adresses 8 bits.
            val twinPlay = db.twin?.codes?.get(Keys.PLAY.id) as? IrCode.Nec
            val addresses = LinkedHashSet<String>()
            twinPlay?.let { addresses += it.addressText }
            db.addresses.forEach { addresses += it.address }
            for (a in 0..0xFF) addresses += String.format(Locale.US, "%02X", a)

            // Étape 2 : la touche Lecture/Pause de la télécommande identique, sur chaque adresse.
            if (twinPlay != null && twinPlay.standardCommand) {
                titles += TITLE_TWIN
                for (a in addresses) {
                    val code = IrCode.Nec.of(a, twinPlay.c0) ?: continue
                    if (!seen.add(code.format())) continue
                    listed += Step(code, titles.size - 1, "Adresse $a · Lecture / Pause", null, Keys.PLAY.id)
                }
            }

            // Étape 3 : tout essayer.
            titles += TITLE_ALL
            val known = db.addresses.associate { it.address to it.commands }
            return ScanPlan(titles, listed, addresses.toList(), known, numericOrder = false)
        }

        /** Les 256 commandes d'une seule adresse, dans l'ordre, pour trouver les touches restantes. */
        fun singleAddress(address: String): ScanPlan? {
            val a = address.trim().uppercase(Locale.US)
            if (IrCode.Nec.of(a, 0) == null) return null
            return ScanPlan(listOf("Toutes les commandes de l'adresse $a"), emptyList(), listOf(a), emptyMap(), numericOrder = true)
        }
    }
}
