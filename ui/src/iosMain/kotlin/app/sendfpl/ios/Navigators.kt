package app.sendfpl.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.ExternalAccessory.EAAccessory
import platform.ExternalAccessory.EAAccessoryDidConnectNotification
import platform.ExternalAccessory.EAAccessoryDidDisconnectNotification
import platform.ExternalAccessory.EAAccessoryManager
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToURL

/** A navigator as the iPhone app lists it: one iOS has connected, or the one last chosen. */
data class Navigator(
    /** Its serial number, which tells two units of one model apart and outlives a renaming. */
    val serial: String,
    val name: String,
    /** The connected accessory, or null while it is out of reach. */
    val accessory: EAAccessory? = null,
    /** The navigator in memory that a demonstration sends to. */
    val demo: Boolean = false,
) {
    /** Whether a send can reach it now. */
    val live: Boolean get() = accessory != null || demo
}

/** What the navigator sheet lists, and what Send aims at. */
data class NavigatorState(
    val list: List<Navigator> = emptyList(),
    val selected: Navigator? = null,
    /** Serial number to the epoch milliseconds of the last upload, which tells two units apart. */
    val lastUsed: Map<String, Long> = emptyMap(),
)

/**
 * The navigators the External Accessory framework has connected that serve [CONNEXT_PROTOCOL],
 * kept current by its connect and disconnect notifications. Send aims at the one the pilot chose,
 * kept by serial number across launches, or at the first connected while none was chosen. A
 * [demo] launch lists the navigator in memory alone, and [demonstrate] lists it beside the others.
 * Read and changed on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
class Navigators(private val memory: NavigatorMemory, private val demo: Boolean) {
    private val _state = MutableStateFlow(NavigatorState())
    val state: StateFlow<NavigatorState> = _state.asStateFlow()

    /**
     * Whether the navigator in memory is listed, whether Send aims at it, and when it took its
     * last plan. All three last for the launch, and the memory's file holds real navigators alone.
     */
    private var demoListed = demo
    private var demoChosen = demo
    private var demoUsed: Long? = null

    /** Listens for navigators coming and going, and lists those already connected. */
    fun start() {
        if (!demo) {
            EAAccessoryManager.sharedAccessoryManager().registerForLocalNotifications()
            for (name in listOf(EAAccessoryDidConnectNotification, EAAccessoryDidDisconnectNotification)) {
                NSNotificationCenter.defaultCenter.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ ->
                    refresh()
                }
            }
        }
        refresh()
    }

    fun refresh() {
        val accessories = if (demo) {
            emptyList()
        } else {
            EAAccessoryManager.sharedAccessoryManager().connectedAccessories
                .filterIsInstance<EAAccessory>()
                .filter { CONNEXT_PROTOCOL in it.protocolStrings }
                .map { Navigator(field(it.serialNumber), field(it.name), it) }
        }
        val connected = if (demoListed) listOf(DEMO_NAVIGATOR) + accessories else accessories
        val chosen = memory.chosen
        val selected = when {
            demoChosen -> DEMO_NAVIGATOR
            chosen != null -> connected.find { it.serial == chosen.first } ?: Navigator(chosen.first, chosen.second)
            else -> connected.firstOrNull()
        }
        // The chosen navigator stays listed out of reach, so the sheet names what Send aims at.
        val list = if (selected != null && !selected.live) listOf(selected) + connected else connected
        val lastUsed = demoUsed?.let { memory.lastUsed + (DEMO_SERIAL to it) } ?: memory.lastUsed
        _state.value = NavigatorState(list, selected, lastUsed)
    }

    /** Lists the navigator in memory and aims Send at it, until the pilot chooses another. */
    fun demonstrate() {
        demoListed = true
        demoChosen = true
        refresh()
    }

    fun select(n: Navigator) {
        demoChosen = n.demo
        if (!n.demo) memory.choose(n.serial, n.name)
        refresh()
    }

    /** A completed upload, which the sheet shows to tell two units of one model apart. */
    fun recordUpload(n: Navigator) {
        val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
        if (n.demo) demoUsed = now else memory.used(n.serial, now)
        refresh()
    }

    /** iOS's own list of Bluetooth accessories nearby, from which a navigator is paired. */
    fun pair() {
        EAAccessoryManager.sharedAccessoryManager().showBluetoothAccessoryPickerWithNameFilter(null) { _ ->
            NSOperationQueue.mainQueue.addOperationWithBlock { refresh() }
        }
    }

    private companion object {
        const val DEMO_SERIAL = "DEMO"
        // The model's name and DEMO, so the home screen says where Send goes, in any language.
        const val DEMO_NAME = "GPS 175 DEMO"
        val DEMO_NAVIGATOR = Navigator(DEMO_SERIAL, DEMO_NAME, demo = true)
    }
}

/** [s], which an accessory reports, with each control character a space, so it is one field. */
private fun field(s: String): String = s.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("")

/**
 * The navigator chosen and when each took its last plan, in a file of the app's own kept out of
 * backups, as Android keeps both out of its. One line a fact, its fields separated by tabs.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class NavigatorMemory {
    private val file: NSURL? = run {
        val fm = NSFileManager.defaultManager
        val dir = (fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).firstOrNull() as? NSURL)
            ?.URLByAppendingPathComponent("navigators", isDirectory = true) ?: return@run null
        fm.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        // On the directory, so a file written over atomically stays out of backups too.
        dir.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        dir.URLByAppendingPathComponent("navigators.txt")
    }

    /** Serial number and name of the navigator chosen, or null while none is. */
    var chosen: Pair<String, String>? = null
        private set
    var lastUsed: Map<String, Long> = emptyMap()
        private set

    init {
        read()
    }

    fun choose(serial: String, name: String) {
        chosen = serial to name
        write()
    }

    fun used(serial: String, atMillis: Long) {
        lastUsed = lastUsed + (serial to atMillis)
        write()
    }

    private fun read() {
        val url = file ?: return
        val text = NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null) ?: return
        val used = mutableMapOf<String, Long>()
        for (line in text.lines()) {
            val f = line.split('\t')
            when {
                f.size == 3 && f[0] == "chosen" -> chosen = f[1] to f[2]
                f.size == 3 && f[0] == "used" -> f[2].toLongOrNull()?.let { used[f[1]] = it }
            }
        }
        lastUsed = used
    }

    private fun write() {
        val url = file ?: return
        val text = buildString {
            chosen?.let { (serial, name) -> append("chosen\t${field(serial)}\t${field(name)}\n") }
            for ((serial, at) in lastUsed) append("used\t${field(serial)}\t$at\n")
        }
        NSString.create(string = text).writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}
