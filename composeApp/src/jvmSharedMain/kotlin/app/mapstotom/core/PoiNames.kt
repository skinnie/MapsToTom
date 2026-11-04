package app.mapstotom.core

import java.text.Normalizer

/*
 * POI category names in the TomTom's own language. TomTom's built-in categories are stored in the map by number
 * and named from its language data, so they follow a language change by themselves. An added category's name is
 * just its file name, so the app names its own categories in the language the TomTom reports (UserLanguage in
 * ttgo.bif), renames them when it connects, and leaves a small start-up script on the card that renames them on
 * the TomTom itself after a language change (see [poiLangScript]).
 */

/** Languages with translated names, in the order of every name list below; anything else gets English. */
val POI_LANGUAGES = listOf("en", "pt", "es", "fr", "de", "it", "nl")

/** The TomTom's menu language as a code from [POI_LANGUAGES], from ttgo.bif's UserLanguage ("Português", "Deutsch"…). */
fun tomtomLanguage(bif: Map<String, String>): String {
    val l = ascii(bif["UserLanguage"].orEmpty()).lowercase()
    return LANGUAGE_PREFIXES.entries.firstOrNull { (_, prefixes) -> prefixes.any { l.startsWith(it.lowercase()) } }?.key ?: "en"
}

/** UserLanguage starts with one of these, per language (also used as shell patterns in the start-up script). */
private val LANGUAGE_PREFIXES = linkedMapOf(
    "pt" to listOf("Portug"), "es" to listOf("Espa", "Castell"), "fr" to listOf("Fran"),
    "de" to listOf("Deutsch"), "it" to listOf("Italian"), "nl" to listOf("Nederlands", "Vlaams"),
)

/**
 * One category this app names: [names] in [POI_LANGUAGES] order (accents allowed here, see [nameIn]),
 * [source] added at the end ("Piscinas (OSM)"), and [older] names it had before, so those are renamed too.
 */
class TranslatedCategory(val source: String, val names: List<String>, val older: List<String> = emptyList()) {
    init { require(names.size == POI_LANGUAGES.size) }

    /**
     * The file name (without extension) in [lang]. Plain ASCII, because an accent in a POI file name is known to
     * come out wrong on some TomTom cards; German umlauts become ae/oe/ue.
     */
    fun nameIn(lang: String): String = safeFilename(ascii(names.getOrNull(POI_LANGUAGES.indexOf(lang)) ?: names[0]) + " ($source)")

    /** Every name this category may have on the card, lower case. */
    fun knownNames(): Set<String> = (POI_LANGUAGES.map { nameIn(it) } + older).map { it.lowercase() }.toSet()
}

private fun osm(type: OsmPoiType, vararg names: String) = type to TranslatedCategory("OSM", names.toList(), listOf(type.category))

private val OSM_NAMES: Map<OsmPoiType, TranslatedCategory> = mapOf(
    osm(OsmPoiType.FUEL, "Fuel stations", "Postos de combustível", "Gasolineras", "Stations-service", "Tankstellen", "Distributori di benzina", "Tankstations"),
    osm(OsmPoiType.LPG, "LPG (autogas)", "GPL Auto", "GLP Autogás", "GPL", "Autogas (LPG)", "GPL", "LPG"),
    osm(OsmPoiType.EV, "EV chargers", "Carregadores elétricos", "Puntos de recarga", "Bornes de recharge", "Ladestationen", "Colonnine di ricarica", "Laadpalen"),
    osm(OsmPoiType.MOTORHOME, "Motorhome areas", "Áreas de autocaravanas", "Áreas de autocaravanas", "Aires de camping-car", "Wohnmobil-Stellplätze", "Aree camper", "Camperplaatsen"),
    osm(OsmPoiType.CAMPSITE, "Campsites", "Parques de campismo", "Campings", "Campings", "Campingplätze", "Campeggi", "Campings"),
    osm(OsmPoiType.SUPERMARKET, "Supermarkets", "Supermercados", "Supermercados", "Supermarchés", "Supermärkte", "Supermercati", "Supermarkten"),
    osm(OsmPoiType.PHARMACY, "Pharmacies", "Farmácias", "Farmacias", "Pharmacies", "Apotheken", "Farmacie", "Apotheken"),
    osm(OsmPoiType.BEACH, "Beaches", "Praias", "Playas", "Plages", "Strände", "Spiagge", "Stranden"),
    osm(OsmPoiType.TOILETS, "Public toilets", "Casas de banho públicas", "Aseos públicos", "Toilettes publiques", "Öffentliche Toiletten", "Bagni pubblici", "Openbare toiletten"),
    osm(OsmPoiType.DRINKING_WATER, "Drinking water", "Água potável", "Agua potable", "Eau potable", "Trinkwasser", "Acqua potabile", "Drinkwater"),
    osm(OsmPoiType.VIEWPOINT, "Viewpoints", "Miradouros", "Miradores", "Points de vue", "Aussichtspunkte", "Punti panoramici", "Uitzichtpunten"),
    osm(OsmPoiType.PICNIC, "Picnic sites", "Parques de merendas", "Áreas de picnic", "Aires de pique-nique", "Picknickplätze", "Aree picnic", "Picknickplaatsen"),
    osm(OsmPoiType.POOL, "Swimming pools", "Piscinas", "Piscinas", "Piscines", "Schwimmbäder", "Piscine", "Zwembaden"),
    osm(OsmPoiType.SPEED_CAMERA, "Speed cameras", "Radares", "Radares", "Radars", "Blitzer", "Autovelox", "Flitspalen"),
)

val OsmPoiType.translated: TranslatedCategory get() = OSM_NAMES.getValue(this)
fun OsmPoiType.categoryName(lang: String): String = translated.nameIn(lang)

/** iOverlander's categories (its .gpi files name them in English), keyed by that English name. */
private val IOVERLANDER: Map<String, TranslatedCategory> = listOf(
    listOf("Established Campground", "Parque de campismo", "Camping establecido", "Camping", "Campingplatz", "Campeggio", "Camping"),
    listOf("Informal Campsite", "Campismo informal", "Acampada informal", "Bivouac informel", "Inoffizieller Stellplatz", "Campeggio informale", "Informele kampeerplek"),
    listOf("Wild Camping", "Campismo selvagem", "Acampada libre", "Camping sauvage", "Wildcampen", "Campeggio libero", "Wildkamperen"),
    listOf("Water", "Água", "Agua", "Eau", "Wasser", "Acqua", "Water"),
    listOf("Showers", "Duches", "Duchas", "Douches", "Duschen", "Docce", "Douches"),
    listOf("Laundromat", "Lavandaria", "Lavandería", "Laverie", "Waschsalon", "Lavanderia", "Wasserette"),
    listOf("Sanitation Dump Station", "Despejo de águas", "Vaciado de aguas", "Vidange", "Entsorgungsstation", "Scarico acque", "Loospunt"),
    listOf("Overnight Prohibited", "Pernoita proibida", "Pernocta prohibida", "Nuit interdite", "Übernachten verboten", "Sosta notturna vietata", "Overnachten verboden"),
    listOf("Hotel", "Hotel", "Hotel", "Hôtel", "Hotel", "Hotel", "Hotel"),
    listOf("Hostel", "Hostel", "Albergue", "Auberge", "Hostel", "Ostello", "Hostel"),
    listOf("Restaurant", "Restaurante", "Restaurante", "Restaurant", "Restaurant", "Ristorante", "Restaurant"),
    listOf("Fuel Station", "Posto de combustível", "Gasolinera", "Station-service", "Tankstelle", "Distributore", "Tankstation"),
    listOf("Propane", "Gás propano", "Propano", "Propane", "Propangas", "Propano", "Propaan"),
    listOf("Mechanic and Parts", "Mecânico e peças", "Mecánico y repuestos", "Garage", "Werkstatt", "Meccanico", "Garage"),
    listOf("Short-term Parking", "Estacionamento", "Aparcamiento", "Parking", "Parkplatz", "Parcheggio", "Parkeren"),
    listOf("Tourist Attraction", "Atração turística", "Atracción turística", "Site touristique", "Sehenswürdigkeit", "Attrazione turistica", "Bezienswaardigheid"),
    listOf("Shopping", "Compras", "Compras", "Commerces", "Einkaufen", "Negozi", "Winkels"),
    listOf("Medical", "Saúde", "Médico", "Médical", "Medizinisch", "Medico", "Medisch"),
    listOf("Wifi", "Wi-Fi", "Wi-Fi", "Wi-Fi", "WLAN", "Wi-Fi", "Wifi"),
).associate { n -> n[0].lowercase() to TranslatedCategory("iOverlander", n, listOf("iOverlander ${n[0]}")) }

/** The translated entry for a category of a .gpi file titled [fileTitle], or null when there's none. */
fun gpiTranslated(fileTitle: String?, category: String): TranslatedCategory? =
    if (fileTitle.orEmpty().contains("ioverlander", ignoreCase = true)) IOVERLANDER[category.trim().lowercase()] else null

/** Every category the app renames with the language. */
val TRANSLATED_CATEGORIES: List<TranslatedCategory> get() = OSM_NAMES.values + IOVERLANDER.values

/** "Água potável" → "Agua potavel", "Strände" → "Straende". */
internal fun ascii(s: String): String {
    val de = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
    return Normalizer.normalize(de, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}

/**
 * Renames the app's categories in [mapDir] to [lang] (their .ov2, .bmp and .ogg together).
 * Returns "old → new" for each one renamed; categories already in [lang], or not the app's, are left alone.
 */
fun renameTranslatedCategories(fs: DeviceFs, mapDir: String, lang: String): List<String> {
    val names = fs.list(mapDir)
    val done = mutableListOf<String>()
    for (c in TRANSLATED_CATEGORIES) {
        val target = c.nameIn(lang)
        val known = c.knownNames()
        for (ov2 in names.filter { it.endsWith(".ov2", true) && it.dropLast(4).lowercase() in known }) {
            val base = ov2.dropLast(4)
            if (base == target) continue
            if (names.any { it.equals("$target.ov2", true) }) continue // both exist: leave it to the user
            for (ext in listOf("ov2", "bmp", "ogg")) {
                val f = names.firstOrNull { it.equals("$base.$ext", true) } ?: continue
                fs.rename(join(mapDir, f), "$target.$ext")
            }
            done += "$base → $target"
        }
    }
    return done
}

// ------------------------------------------------------------------ on the TomTom: rename at start

/** Where the start-up script and its note of the last language live on the card. */
const val BOOT_DIR = "mapstotom"
const val BOOT_SCRIPT = "$BOOT_DIR/poilang.sh"
const val BOOT_LAST_LANG = "$BOOT_DIR/lastlang"
private const val HOOK = "[ -f /mnt/sdcard/$BOOT_SCRIPT ] && sh /mnt/sdcard/$BOOT_SCRIPT"

/**
 * A shell script for the TomTom's start, run from the card's ttn before the navigation program: when the menu
 * language differs from the one it saw last time, it renames the app's categories in [mapDir]. Otherwise it reads
 * two small files and stops. Only shell built-ins and mv, so it costs nothing measurable on a normal start.
 */
fun poiLangScript(mapDir: String): String {
    val cases = LANGUAGE_PREFIXES.entries.joinToString(" ") { (lang, p) ->
        p.joinToString("|") { "$it*" } + ") n=${POI_LANGUAGES.indexOf(lang) + 1};;"
    }
    val lines = TRANSLATED_CATEGORIES.joinToString("\n") { c ->
        "t " + (POI_LANGUAGES.map { c.nameIn(it) } + c.older).joinToString(" ") { "\"$it\"" }
    }
    return """
        |#!/bin/sh
        |# MapsToTom: names this app's POI categories in the TomTom's menu language. Run at start by the card's ttn.
        |# Does nothing unless the language changed since the last start. Delete this file to stop it.
        |R=${'$'}{R:-/mnt/sdcard}
        |L=
        |while IFS='=' read -r k v; do [ "${'$'}k" = UserLanguage ] && L=${'$'}v && break; done < "${'$'}R/ttgo.bif"
        |P=
        |[ -f "${'$'}R/$BOOT_LAST_LANG" ] && read -r P < "${'$'}R/$BOOT_LAST_LANG"
        |[ "${'$'}L" = "${'$'}P" ] && exit 0
        |case "${'$'}L" in $cases *) n=1;; esac
        |cd "${'$'}R/$mapDir" || exit 0
        |# t <name in each language: en pt es fr de it nl> [older names]
        |t() {
        |  eval "new=\"\${'$'}{${'$'}n}\""
        |  for o; do
        |    [ "${'$'}o" = "${'$'}new" ] && continue
        |    [ -f "${'$'}o.ov2" ] || continue
        |    [ -f "${'$'}new.ov2" ] && continue
        |    for e in ov2 bmp ogg; do [ -f "${'$'}o.${'$'}e" ] && mv "${'$'}o.${'$'}e" "${'$'}new.${'$'}e"; done
        |  done
        |}
        |$lines
        |echo "${'$'}L" > "${'$'}R/$BOOT_LAST_LANG"
        |""".trimMargin()
}

/**
 * Puts the start-up rename on a TomTom whose card runs its own ttn script (a modded system): writes the script
 * and the current language, and adds one line to ttn the first time, keeping ttn's old copy next to the script.
 * Returns true when the line was added now; false when it was already there or the card has no ttn script.
 */
fun installBootRename(fs: DeviceFs, mapDir: String): Boolean {
    val ttn = fs.read("ttn")?.toString(Charsets.UTF_8) ?: return false
    if (!ttn.startsWith("#!")) return false
    // The language exactly as the script reads it (a \r at the end included), so it doesn't rename again.
    val userLanguage = fs.read("ttgo.bif")?.toString(Charsets.UTF_8)?.split('\n')
        ?.firstOrNull { it.startsWith("UserLanguage=") }?.substringAfter('=') ?: ""
    val script = poiLangScript(mapDir).toByteArray()
    if (!(fs.read(BOOT_SCRIPT)?.contentEquals(script) ?: false)) { fs.mkdirs(BOOT_DIR); fs.write(BOOT_SCRIPT, script) }
    fs.write(BOOT_LAST_LANG, "$userLanguage\n".toByteArray())
    if (HOOK in ttn) return false
    if (!fs.exists("$BOOT_DIR/ttn.before-mapstotom")) fs.write("$BOOT_DIR/ttn.before-mapstotom", ttn.toByteArray())
    // Right after the first line (#!/bin/sh), so it runs before the navigation program starts.
    val eol = if ("\r\n" in ttn) "\r\n" else "\n"
    val first = ttn.substringBefore('\n') + "\n"
    val added = first + "$eol# MapsToTom: POI category names follow the menu language (delete $BOOT_SCRIPT to stop)$eol$HOOK$eol" + ttn.substring(first.length)
    fs.write("ttn", added.toByteArray())
    return true
}

/** Whether [mapDir] holds any of the app's translated categories. */
fun hasTranslatedCategories(fs: DeviceFs, mapDir: String): Boolean {
    val known = TRANSLATED_CATEGORIES.flatMap { it.knownNames() }.toSet()
    return fs.list(mapDir).any { it.endsWith(".ov2", true) && it.dropLast(4).lowercase() in known }
}
