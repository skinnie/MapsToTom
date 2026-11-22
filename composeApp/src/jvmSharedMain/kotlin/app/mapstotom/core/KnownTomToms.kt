package app.mapstotom.core

/** A TomTom this app has met, by USB or through the car link. The car link can't read the card, so sending
 *  QuickGPSFix over Bluetooth uses what is remembered here. [engine] is the GL1 GPS program's version. */
data class KnownTomTom(val name: String, val chip: Chip, val engine: String?, val seen: Long) {
    val key get() = "$name|${chip.name}|${engine.orEmpty()}"
    val label get() = "$name, ${chip.label}"
}

private const val MAX_KNOWN = 8
private fun clean(s: String) = s.replace(Regex("[|\\r\\n]"), " ").trim()

fun parseKnownTomToms(text: String?): List<KnownTomTom> = text.orEmpty().lines().mapNotNull { line ->
    val p = line.split('|')
    if (p.size != 4) return@mapNotNull null
    val chip = Chip.entries.find { it.name == p[1] } ?: return@mapNotNull null
    KnownTomTom(p[0], chip, p[2].ifEmpty { null }, p[3].toLongOrNull() ?: return@mapNotNull null)
}

fun formatKnownTomToms(list: List<KnownTomTom>) =
    list.joinToString("\n") { "${clean(it.name)}|${it.chip.name}|${clean(it.engine.orEmpty())}|${it.seen}" }

/** The list with [t] first, replacing its older entry. */
fun rememberTomTom(list: List<KnownTomTom>, t: KnownTomTom) = (listOf(t) + list.filter { it.key != t.key }).take(MAX_KNOWN)

/** What the helper on the card reported over Bluetooth, or null when it gave no usable GPS type. */
fun knownFromReport(model: String?, gpsType: Int?, gpsVersion: String?, now: Long): KnownTomTom? {
    val chip = chipFromCode(gpsType ?: return null)
    val engine = gpsVersion?.takeIf { chip == Chip.GL1 && it.isNotBlank() }?.let { "Global Locate GLL $it" }
    return KnownTomTom(model?.takeIf { it.isNotBlank() } ?: "TomTom", chip, engine, now)
}
