package app.mapstotom.core

/*
 * NavCore kitchen: what a TomTom's card really runs, and the "device model" community systems make NavCore act as.
 * Community ttsystem images read `model:<word>` from Config/setconfig.txt at each full start, and NavCore turns its
 * features on and off by model id. The table comes from their /etc/rc (docs/navcore-survey.md).
 */

class KitchenModel(val word: String, val id: Int?, val label: String, val note: String)

val KITCHEN_MODELS = listOf(
    KitchenModel("ori", null, "Original", "What this TomTom really is"),
    KitchenModel("x40", 24, "GO x40", "Live services, needed for the car link"),
    KitchenModel("x40light3", 36, "XL LIVE IQ Routes", "Live, without Plus traffic"),
    KitchenModel("x50", 37, "GO x50", "A common choice on community systems"),
    KitchenModel("x40light", 34, "ONE IQ Routes", "IQ Routes, for small screens"),
    KitchenModel("x40light2", 35, "XL IQ Routes", "IQ Routes, for XL screens"),
    KitchenModel("x30", 25, "GO x30", ""),
    KitchenModel("x20", 18, "GO x20", ""),
    KitchenModel("xls", 16, "XLS", ""),
    KitchenModel("rider", 17, "Rider", ""),
    KitchenModel("start", 39, "Start", ""),
    KitchenModel("start2", 43, "Start 2", ""),
)

fun kitchenModel(word: String?) = KITCHEN_MODELS.firstOrNull { it.word == word }

class KitchenInfo(
    val navcore: String?, val build: String?, val systemDate: String?, val kernel: String?, val gpsProgram: String?,
    /** The model in setconfig.txt; null without one (the script then picks one by screen type). */
    val model: String?,
    /** What the TomTom says it used at its last full start. */
    val usedAtLastStart: String?,
    val canChooseModel: Boolean,
    /** Why the model can't be chosen, when it can't. */
    val blocked: String?,
)

fun readKitchen(fs: DeviceFs, bif: Map<String, String>): KitchenInfo {
    val sc = readSetConfig(fs)
    val blocked = when {
        sc == null -> "This TomTom runs a stock system, which has no device model setting. Community 9.5xx NavCores have it."
        sc.hasModelId -> "${sc.path} sets a modelid, which overrides the model. Remove that line to choose here."
        else -> null
    }
    val version = bif["ApplicationVersionVersionNumber"]?.let { v -> if (v.length == 4) "${v[0]}.${v.substring(1)}" else v }
    return KitchenInfo(version, bif["ApplicationVersion"], bif["RamDiskVersion"], bif["LinuxVersion"], bif["GPSFirmwareVersion"],
        sc?.model, readLastStart(fs)?.emulation, blocked == null, blocked)
}
