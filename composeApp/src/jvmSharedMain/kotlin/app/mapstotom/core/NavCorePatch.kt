package app.mapstotom.core

/*
 * Patches NavCore 9.541 so it uses a phone's Bluetooth dial-up for Live services (the car link). Only the changed bytes
 * ship with MapsToTom (files/navcore/9541-carlink.json, made by tools/navcore/make_patch.py), and they are applied to
 * the user's own file. The patch writes to fixed addresses, so it is only applied when every one of those places holds
 * the expected bytes: on other code it would overwrite the wrong instructions. Builds that match but aren't the one
 * tested on a real TomTom are allowed, at the user's own risk. Map licence checks are not touched.
 */

const val NAVCORE_PATH = "PNDNavigator"
const val NAVCORE_BACKUP = "PNDNavigator.mapstotom-orig"
private const val NAVCORE_NEW = "PNDNavigator.mapstotom-new"
private const val NAVCORE_MAX_SIZE = 32_000_000L
private const val NAVCORE_ORIGINAL_MD5 = "732f12d5ce5f7c4341ac149ef1705183"
/** This patch, and the same patch with the start-up legal notice also skipped. */
private val NAVCORE_PATCHED_MD5 = setOf("d33324a6aa977197c4ad7e531727b8d2", "fa65e1f18a470d6b3f8f1b29e6c5e7de")

/** Where the TomTom looks for its Live services: the phone, on the car link's PPP address. */
val CAR_LINK_DISCOVERY = mapOf(
    "servicediscovery.txt" to "192.168.77.1:8080\n/proxy/directory.php\n",
    "herculesdiscovery.txt" to "192.168.77.1:8080\n/services/directory.php\n",
)
private const val KEPT = ".mapstotom-orig"

/** ORIGINAL: the tested build. COMPATIBLE: another build with the same code at every patch site. */
enum class NavCoreState { MISSING, ORIGINAL, COMPATIBLE, PATCHED, OTHER }

/** Whether the card's program has the Live code, or null if it isn't on the card. */
fun navCoreHasLiveCode(fs: DeviceFs): Boolean? =
    if (!fs.exists(NAVCORE_PATH) || fs.size(NAVCORE_PATH) > NAVCORE_MAX_SIZE) null else fs.read(NAVCORE_PATH)?.let(::hasLiveCode)

fun navCoreState(fs: DeviceFs, patch: NavCorePatch?): NavCoreState {
    if (!fs.exists(NAVCORE_PATH)) return NavCoreState.MISSING
    if (fs.size(NAVCORE_PATH) > NAVCORE_MAX_SIZE) return NavCoreState.OTHER
    val data = fs.read(NAVCORE_PATH) ?: return NavCoreState.MISSING
    val sum = md5(data)
    return when {
        sum == NAVCORE_ORIGINAL_MD5 -> NavCoreState.ORIGINAL
        sum in NAVCORE_PATCHED_MD5 || patch?.isApplied(data) == true -> NavCoreState.PATCHED
        patch?.fits(data) == true -> NavCoreState.COMPATIBLE
        else -> NavCoreState.OTHER
    }
}

class NavCorePatch(val name: String, val from: String, val to: String, val ranges: List<Spot>) {
    /** One patched place: the bytes expected there, and what they become. */
    class Spot(val offset: Int, val old: ByteArray, val new: ByteArray)

    private fun holds(data: ByteArray, offset: Int, bytes: ByteArray) =
        offset + bytes.size <= data.size && bytes.indices.all { data[offset + it] == bytes[it] }

    /** Every patch site holds the expected code, so the patch lands on the right instructions. */
    fun fits(data: ByteArray) = ranges.all { holds(data, it.offset, it.old) }

    fun isApplied(data: ByteArray) = ranges.all { holds(data, it.offset, it.new) }

    /** The patched file, or an error if [data] has different code at any patch site. */
    fun apply(data: ByteArray): ByteArray {
        require(fits(data)) { "This NavCore has different code where the patch goes, so it can't be patched" }
        val out = data.copyOf()
        for (s in ranges) s.new.copyInto(out, s.offset)
        if (md5(data) == from) check(md5(out) == to) { "The patched file doesn't match" }
        return out
    }

    companion object {
        fun parse(json: String): NavCorePatch {
            val m = Json.parse(json) as Map<*, *>
            fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val ranges = (m["ranges"] as List<*>).map { r ->
                r as Map<*, *>
                Spot((r["o"] as Long).toInt(), bytes(r["a"] as String), bytes(r["b"] as String))
            }
            return NavCorePatch(m["name"] as String, m["from"] as String, m["to"] as String, ranges)
        }
    }
}

/** Writes [data] beside the program, checks it, and only then swaps it in. */
private fun replaceNavCore(fs: DeviceFs, data: ByteArray) {
    fs.write(NAVCORE_NEW, data)
    check(fs.read(NAVCORE_NEW)?.contentEquals(data) == true) { "The card didn't store the new file correctly" }
    check(fs.delete(NAVCORE_PATH) && fs.rename(NAVCORE_NEW, NAVCORE_PATH)) { "Couldn't replace $NAVCORE_PATH" }
}

/** Patches NavCore, keeps the original beside it, points the Live services at the phone and turns Live on. */
fun installCarLinkPatch(fs: DeviceFs, patch: NavCorePatch) {
    val sc = readSetConfig(fs) ?: error("This TomTom has no setconfig.txt, so Live services can't be turned on.")
    require(!sc.hasModelId) { "${sc.path} has a modelid line, which overrides the model. Remove that line first." }
    val original = fs.read(NAVCORE_PATH) ?: error("No $NAVCORE_PATH on this TomTom")
    val patched = patch.apply(original)
    val kept = fs.read(NAVCORE_BACKUP)
    if (kept == null) {
        fs.write(NAVCORE_BACKUP, original)
        check(fs.read(NAVCORE_BACKUP)?.contentEquals(original) == true) { "The card didn't store the backup correctly" }
    } else check(kept.contentEquals(original)) { "$NAVCORE_BACKUP isn't this TomTom's current program" }
    replaceNavCore(fs, patched)
    for ((file, text) in CAR_LINK_DISCOVERY) {
        val old = fs.read(file)
        if (old != null && !old.contentEquals(text.toByteArray()) && !fs.exists(file + KEPT)) fs.write(file + KEPT, old)
        fs.write(file, text.toByteArray())
    }
    setLive(fs, true)
}

/** Puts the original NavCore and discovery files back and turns Live off. */
fun removeCarLinkPatch(fs: DeviceFs, patch: NavCorePatch) {
    val original = fs.read(NAVCORE_BACKUP) ?: error("The original program ($NAVCORE_BACKUP) isn't on this TomTom")
    check(patch.fits(original)) { "$NAVCORE_BACKUP isn't the original program" }
    replaceNavCore(fs, original)
    fs.delete(NAVCORE_BACKUP)
    for (file in CAR_LINK_DISCOVERY.keys) {
        val old = fs.read(file + KEPT)
        if (old != null) { fs.write(file, old); fs.delete(file + KEPT) } else if (fs.exists(file)) fs.delete(file)
    }
    if (readSetConfig(fs)?.live == true) setLive(fs, false)
}

/** NavCore 9.5xx: the versions the car link patch is made for, and which carry the later GPS programs. */
fun isNavCore95(version: String?) = version?.startsWith("9.5") == true

const val NAVCORE_PORTING_GUIDE = "https://github.com/skinnie/MapsToTom/blob/main/docs/navcore-survey.md#porting-the-patch"

/** Whether this program has the Live services code the patch switches on. */
fun hasLiveCode(data: ByteArray): Boolean {
    val key = "Hercules".toByteArray()
    return (0..data.size - key.size).any { i -> data[i] == key[0] && key.indices.all { data[i + it] == key[it] } }
}

/** [canPort]: the NavCore has the Live code, so the patch could be ported to it. */
class CarLinkAdvice(val title: String, val detail: String, val canPort: Boolean = false)

/**
 * Why the car link can't be installed on this TomTom, and what to do. Null when it can be, or is already.
 * [liveCode] is null when the program isn't on the card to read; then 8.x and 9.x are taken to have it.
 */
fun carLinkAdvice(version: String?, state: NavCoreState, hasSetConfig: Boolean, liveCode: Boolean? = null): CarLinkAdvice? {
    val name = version?.let { "NavCore $it" } ?: "this NavCore"
    val forums = "GPS and navigation device forums (unofficial, at your own risk)"
    val major = version?.substringBefore('.')?.toIntOrNull()
    return when {
        state == NavCoreState.PATCHED -> null
        state == NavCoreState.ORIGINAL || state == NavCoreState.COMPATIBLE ->
            if (hasSetConfig) null else CarLinkAdvice("${name.replaceFirstChar { it.uppercase() }} fits, but needs a community system",
                "The patch fits, but this TomTom's system has no setconfig.txt, which is what turns Live services on. " +
                    "Community 9.5xx NavCores have it: look for one for your model on $forums, then come back.")
        liveCode ?: (major == 8 || major == 9) -> CarLinkAdvice("Car link patch not ported to $name yet",
            "This NavCore has the Live services code, but the patch still has to be ported to it. If you need it, you can build " +
                "the port yourself with the guide. Or move to a 9.5xx NavCore, which already works: check TomTom HOME, or look for " +
                "one for your model on $forums.", canPort = true)
        else -> CarLinkAdvice("Car link isn't available on $name",
            "This NavCore doesn't have the Live services code.\n" +
                "1. Check TomTom HOME for a NavCore update.\n" +
                "2. If it has none, or you can't update, look for a 9.5xx NavCore for your model on $forums.\n" +
                "3. Come back, and MapsToTom will check whether it fits.")
    }
}
