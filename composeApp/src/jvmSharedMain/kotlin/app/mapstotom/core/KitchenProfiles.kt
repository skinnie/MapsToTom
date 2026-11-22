package app.mapstotom.core

/*
 * The plain-language half of the NavCore kitchen. MEASURED_FEATURES was read from NavCore 9.541 itself: it was run in an
 * emulator with a ONE v4's hardware flags and each model id, and the Features line it wrote to ttgo.bif was kept.
 * The x40 row matches the real device's list exactly. Other NavCores or devices can differ.
 */
private val MEASURED_FEATURES: Map<Int, Set<String>> = mapOf(
    15 to "Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham ExternalModem GPRSEngine HDTraffic HTTPActivation Handsfree HomeBackup Itinerary Leeway LinkVolumeToSpeed MapOverlays MapShare MultipleMaps Newyork PermanentMapCfg PhotoBMPViewer PhotoJPGViewer PlusBuddies PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusTraffic PlusTripReporting PlusWeather Prague SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser WarningSounds",
    16 to "Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham ExternalModem GPRSEngine HDTraffic HTTPActivation Handsfree HomeBackup Itinerary Leeway LinkVolumeToSpeed MapOverlays MapShare MultipleMaps Newyork PermanentMapCfg PhoneFeatures PhotoBMPViewer PhotoJPGViewer PlusBuddies PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusTraffic PlusTripReporting PlusWeather Prague RDSTMC SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser WarningSounds",
    17 to "Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham ExternalModem GPRSEngine HDTraffic HTTPActivation Handsfree HomeBackup Itinerary Leeway MapOverlays MultipleMaps PermanentMapCfg PhoneFeatures PhotoBMPViewer PhotoJPGViewer PlusBuddies PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusTraffic PlusTripReporting PlusWeather Prague SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser WarningSounds",
    18 to "ArrivalSounds Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham ExternalModem FrequentDestinations GPRSEngine HDTraffic HTTPActivation Handsfree HidRemote HomeBackup IPOD Itinerary Leeway LinkVolumeToNoise MP3 MapOverlays MapShare Microphone MultipleMaps MusicPlayer Newyork PermanentMapCfg PhoneFeatures PhotoBMPViewer PhotoJPGViewer PhotoViewer PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusTraffic PlusTripReporting PlusWeather Prague RDSTMC RichPOI SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser VoiceRecording WarningSounds",
    24 to "ArrivalSounds Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham ExternalModem ExtraSounds FrequentDestinations GPRSEngine HDTraffic HTTPActivation Handsfree Hercules HerculesTraffic HidRemote HomeBackup IPOD Itinerary Leeway LinkVolumeToNoise LiveServices MapOverlays MapShare Microphone MultipleMaps MusicPlayer Newyork OnDevicePayments Oxford PermanentMapCfg PhoneFeatures PhotoBMPViewer PhotoJPGViewer PhotoViewer PlusFuelPrices PlusOrRDSTMCTraffic PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusTripReporting Prague RDSTMC RichPOI SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories UserSkin VoiceBrowser VoiceRecording WarningSounds",
    25 to "ArrivalSounds Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham ExternalModem FrequentDestinations GPRSEngine HDTraffic HTTPActivation Handsfree HidRemote HomeBackup IPOD Itinerary Leeway LinkVolumeToNoise MapOverlays MapShare Microphone MultipleMaps MusicPlayer Newyork PermanentMapCfg PhoneFeatures PhotoBMPViewer PhotoJPGViewer PhotoViewer PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusTraffic PlusTripReporting PlusWeather Prague RDSTMC RichPOI SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser VoiceRecording WarningSounds",
    34 to "Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham Handsfree HomeBackup Itinerary Leeway LinkVolumeToSpeed MapOverlays MapShare MultipleMaps Newyork PermanentMapCfg PlusBuddies PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusWeather RDSTMC RichPOI SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser WarningSounds",
    35 to "Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham Handsfree HomeBackup Itinerary Leeway LinkVolumeToSpeed MapOverlays MapShare MultipleMaps Newyork PermanentMapCfg PlusBuddies PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusWeather RDSTMC RichPOI SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser WarningSounds",
    36 to "Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham ExternalModem GPRSEngine HDTraffic HTTPActivation Handsfree Hercules HerculesTraffic HomeBackup Itinerary Leeway LinkVolumeToSpeed LiveServices MapOverlays MapShare MultipleMaps Newyork OnDevicePayments Oxford PermanentMapCfg PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusTripReporting PlusWeather Prague RDSTMC RichPOI SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser WarningSounds",
    37 to "Bluetooth CarSymbol Caymann DangerZonesCameras Documents Durham ExtraSounds FrequentDestinations HDTraffic Handsfree HidRemote HomeBackup Itinerary Leeway LinkVolumeToNoise MapOverlays MapShare Microphone MultipleMaps Newyork OnDevicePayments PermanentMapCfg PhoneFeatures PhotoBMPViewer PhotoJPGViewer PhotoViewer PlusFuelPrices PlusOrRDSTMCTraffic PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusTripReporting RDSTMC RichPOI SDK SDSlot SMSSupported SettingsOverride TextureRendering Traffic USBAccessories UserSkin VoiceBrowser WarningSounds",
    39 to "Bluetooth CarSymbol Caymann CaymannAlwaysOn DangerZonesCameras Durham Handsfree HomeBackup Leeway LinkVolumeToSpeed MapShare MultipleMaps Newyork PermanentMapCfg PlusBuddies PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusWeather SDK SDSlot SMSSupported SettingsOverride SimpleMapBrowserButtonBar USBAccessories VoiceBrowser",
    43 to "Bluetooth CarSymbol Caymann DangerZonesCameras Durham Handsfree HomeBackup Itinerary Leeway LinkVolumeToSpeed MapOverlays MapShare MultipleMaps Newyork PermanentMapCfg PlusBuddies PlusDownloadDynamic PlusDownloadGeneral PlusDownloadMap PlusDownloadPOI PlusDownloadRingTone PlusDownloadScheme PlusDownloadUpgrade PlusDownloadVoice PlusEphemeris PlusFuelPrices PlusMessageNotification PlusOrRDSTMCTraffic PlusPushChannel PlusRoadConditions PlusSafetyCamerasDynamic PlusSafetyCamerasStatic PlusWeather RDSTMC RouteInfoBackToSummary SDK SDSlot SMSSupported SettingsOverride Traffic USBAccessories VoiceBrowser WarningSounds ZeroVolumeSupported"
).mapValues { it.value.split(' ').toSet() }

/** [needs] is the hardware flag the feature depends on, when it has one. */
class FeatureInfo(val name: String, val what: String, val needs: String? = null, val missing: String? = null)

val FEATURE_INFO: Map<String, FeatureInfo> = mapOf(
    "LiveServices" to FeatureInfo("Live services", "Traffic, weather and other online services. The car link needs this."),
    "HDTraffic" to FeatureInfo("HD Traffic", "Detailed live traffic on your route."),
    "Oxford" to FeatureInfo("Local search", "Search online for shops and places near you."),
    "GPRSEngine" to FeatureInfo("Mobile data", "Lets the TomTom go online."),
    "ExternalModem" to FeatureInfo("Data through a phone", "Uses a paired phone or the car link as its modem."),
    "PhoneFeatures" to FeatureInfo("Phone menu", "The extra phone options in the menus."),
    "Handsfree" to FeatureInfo("Hands-free calls", "Make and take calls through the TomTom.", "canrecordaudio", "It has no microphone, so the other person can't hear you."),
    "SMSSupported" to FeatureInfo("Text messages", "Read texts from a paired phone."),
    "Itinerary" to FeatureInfo("Itineraries", "Multi-stop routes, including the ones MapsToTom sends."),
    "Traffic" to FeatureInfo("Traffic menu", "The traffic bar and menu."),
    "RDSTMC" to FeatureInfo("FM traffic receiver", "Free radio traffic through an add-on receiver.", "rdstmc", "It needs the separate FM receiver."),
    "PlusOrRDSTMCTraffic" to FeatureInfo("Traffic sources", "Lets traffic come from online or radio."),
    "MapShare" to FeatureInfo("Map Share", "Fix map mistakes yourself."),
    "MapOverlays" to FeatureInfo("Map overlays", "Extra information drawn on the map."),
    "RichPOI" to FeatureInfo("Richer POI details", "More information on places."),
    "FrequentDestinations" to FeatureInfo("Frequent destinations", "Offers places you often go to."),
    "UserSkin" to FeatureInfo("Custom skins", "Change how the menus look."),
    "Documents" to FeatureInfo("Documents", "Read guides on the device."),
    "WarningSounds" to FeatureInfo("Warning sounds", "Sounds for alerts."),
    "ArrivalSounds" to FeatureInfo("Arrival sounds", "A sound when you arrive."),
    "ExtraSounds" to FeatureInfo("Extra sounds", "More sound options."),
    "LinkVolumeToSpeed" to FeatureInfo("Volume follows speed", "Louder as you drive faster."),
    "LinkVolumeToNoise" to FeatureInfo("Volume follows noise", "Louder in a noisy car.", "canrecordaudio", "It has no microphone to hear the noise."),
    "Microphone" to FeatureInfo("Microphone options", "Settings for a microphone.", "canrecordaudio", "It has no microphone."),
    "VoiceRecording" to FeatureInfo("Record your own voice", "Record navigation voices.", "canrecordaudio", "It has no microphone."),
    "MusicPlayer" to FeatureInfo("Music player", "Play music from the card."),
    "MP3" to FeatureInfo("MP3 playback", "Plays MP3 files."),
    "IPOD" to FeatureInfo("iPod control", "Control an iPod.", "usbhost", "It can't connect to an iPod."),
    "PhotoViewer" to FeatureInfo("Photo viewer", "Look at photos."),
    "PhotoJPGViewer" to FeatureInfo("JPG photos", "Opens JPG pictures."),
    "PhotoBMPViewer" to FeatureInfo("BMP photos", "Opens BMP pictures."),
    "HidRemote" to FeatureInfo("Remote control", "Use a TomTom remote.", "remote", "It needs a TomTom remote."),
    "PlusWeather" to FeatureInfo("Old weather service", "TomTom's older weather service, separate from Live."),
    "PlusTraffic" to FeatureInfo("Old traffic service", "TomTom's older traffic service, separate from Live."),
    "PlusTripReporting" to FeatureInfo("Trip reports", "Sends trip reports online."),
)

class KitchenRecipe(
    val word: String, val id: Int?, val title: String, val summary: String,
    /** Made for a bigger screen than a 320 × 240 unit. */
    val bigScreen: Boolean = false,
    val notes: List<String> = emptyList(),
)

val KITCHEN_RECIPES = listOf(
    KitchenRecipe("x40", 24, "Full Live", "The most features with the car link. Your TomTom uses this now.",
        notes = listOf("Community users report it can feel slower than Original.", "Some menus lead to things your TomTom doesn't have.")),
    KitchenRecipe("x40light3", 36, "Original + Live (closest match)", "The nearest ready-made mix: close to your real TomTom, with Live added.", bigScreen = true,
        notes = listOf("Not tried on a real TomTom yet, so test weather, traffic and the car link after switching.", "It has no phone menu, so pairing a phone may work differently.")),
    KitchenRecipe("ori", 15, "Original", "Your TomTom as it left the factory. Community users often call this the fastest."),
    KitchenRecipe("x40light", 34, "Light", "A smaller feature set for small screens, without online services."),
    KitchenRecipe("x50", 37, "GO x50", "A common community default, without mobile data.",
        notes = listOf("One community report says it was needed for the speed limit panel.")),
    KitchenRecipe("x30", 25, "GO x30", "More media features, using TomTom's old PLUS services.", bigScreen = true),
    KitchenRecipe("x20", 18, "GO x20", "The most features on the list, using TomTom's old PLUS services.", bigScreen = true),
    KitchenRecipe("rider", 17, "Rider", "The motorcycle version."),
    KitchenRecipe("start", 39, "Start", "A very simple TomTom. It removes itineraries and traffic."),
)

fun recipeFor(word: String?) = KITCHEN_RECIPES.firstOrNull { it.word == word }

class DeviceSpecs(
    val name: String?, val version: String?, val ramMb: Int?, val cpuMhz: Int?, val screen: String?,
    val gps: String?, val bluetooth: Boolean?, val microphone: Boolean?,
    /** The TomTom's own hardware flags, when a log of them is on the card. */
    val hardware: Map<String, String>,
)

/** What the TomTom is. The detail comes from a start-up log when the card has one; ttgo.bif always gives the name. */
fun readDeviceSpecs(fs: DeviceFs, bif: Map<String, String>): DeviceSpecs {
    val log = runCatching { fs.list("gl/log").filter { it.startsWith("sys-") && it.endsWith(".txt") }.maxOrNull() }.getOrNull()
        ?.let { fs.read("gl/log/$it")?.toString(Charsets.ISO_8859_1) }.orEmpty().replace("\r", "")
    val hw = log.substringAfter("== hardware\n", "").substringBefore("== ").lines().filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    val ram = Regex("""MemTotal:\s+(\d+) kB""").find(log)?.groupValues?.get(1)?.toInt()?.let { kb -> listOf(16, 32, 64, 128).firstOrNull { it * 1024 >= kb } ?: (kb / 1024) }
    val cpu = Regex("""cpuinfo_max_freq: (\d+)""").find(log)?.groupValues?.get(1)?.toInt()?.div(1000)
    val screen = when (hw["tfttype"]) { "3" -> "Small, 320 × 240"; null -> null; else -> "Type ${hw["tfttype"]}" }
    val gps = when (hw["gpstype"]) { "128", "129" -> "Global Locate GL1"; "130" -> "Global Locate GL2"; null -> null; else -> "SiRF" }
    return DeviceSpecs(bif["DeviceName"], bif["DeviceVersionHW"], ram, cpu, screen, gps,
        hw["bluetooth"]?.let { it == "1" }, hw["canrecordaudio"]?.let { it == "1" }, hw)
}

class RecipeChange(
    val gains: List<FeatureInfo>, val losses: List<FeatureInfo>, val noHardware: List<FeatureInfo>,
    /** Menu items that go away but could never work on this TomTom. */
    val noRealLoss: List<FeatureInfo>, val warnings: List<String>,
)

/** What moving from the features in use now to [to] would change for this TomTom. */
fun compareRecipe(currentFeatures: Set<String>, to: KitchenRecipe, specs: DeviceSpecs): RecipeChange {
    val target = MEASURED_FEATURES[to.id] ?: emptySet()
    fun lacks(i: FeatureInfo) = i.needs != null && specs.hardware[i.needs] == "0"
    val added = (target - currentFeatures).mapNotNull { FEATURE_INFO[it] }
    val warnings = buildList {
        if ("LiveServices" in currentFeatures && "LiveServices" !in target) add("The car link, live traffic and weather will stop working.")
        else if ("GPRSEngine" !in target && "GPRSEngine" in currentFeatures) add("The TomTom will no longer go online.")
        if ("Itinerary" !in target) add("You won't be able to load routes sent by MapsToTom.")
        if (to.bigScreen && specs.screen?.startsWith("Small") == true) add("Made for a bigger screen, so some menus may look cramped or glitchy on yours.")
        addAll(to.notes)
    }
    val removed = (currentFeatures - target).mapNotNull { FEATURE_INFO[it] }
    return RecipeChange(added.filterNot(::lacks), removed.filterNot(::lacks), added.filter(::lacks), removed.filter(::lacks), warnings)
}
