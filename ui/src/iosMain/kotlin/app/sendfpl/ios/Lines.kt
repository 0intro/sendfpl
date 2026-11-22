package app.sendfpl.ios

import app.sendfpl.FileTooLargeException
import app.sendfpl.Line
import app.sendfpl.LinkException
import app.sendfpl.Store
import app.sendfpl.UnopenableFileException
import app.sendfpl.cxp.Carrier
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.CxpEvent
import app.sendfpl.cxp.DemoNavigator
import app.sendfpl.cxp.FrameStream
import app.sendfpl.cxp.TOKEN_LEN
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.ExternalAccessory.EAAccessory
import platform.ExternalAccessory.EAAccessoryDidDisconnectNotification
import platform.ExternalAccessory.EAAccessoryKey
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.fileHandleForReadingFromURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

/** An External Accessory session to [accessory], one per transfer. */
class AccessoryLine(private val accessory: EAAccessory) : Line {
    /**
     * The link, published before its streams are open, so [close] can end it from another thread,
     * and end the wait for them with it.
     */
    @Volatile
    private var link: AccessoryLink? = null

    @Volatile
    private var closed = false

    /** The navigator's going, which ends the transfer where quiet streams wait out a timeout. */
    @Volatile
    private var watch: NSObjectProtocol? = null

    override suspend fun open(onEvent: (CxpEvent) -> Unit): Carrier {
        watch = NSNotificationCenter.defaultCenter.addObserverForName(
            EAAccessoryDidDisconnectNotification, null, NSOperationQueue.mainQueue,
        ) { note ->
            val gone = note?.userInfo?.get(EAAccessoryKey) as? EAAccessory
            if (gone?.connectionID == accessory.connectionID) close()
        }
        val l = AccessoryLink.start(accessory)
        link = l
        // A Cancel that came before the link existed had nothing to close.
        if (closed) l.close()
        try {
            l.awaitOpen()
        } catch (e: LinkException) {
            l.close()
            throw e
        }
        return FrameStream(l, onEvent)
    }

    override fun close() {
        closed = true
        link?.close()
        watch?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
    }
}

/**
 * The navigator in memory, for a demonstration and for the UI tests: it holds the token of the
 * credential the app has, so the app's own authenticates, and takes the upload in silence.
 */
class DemoLine(private val store: Store) : Line {
    @Volatile
    private var navigator: DemoNavigator? = null

    override suspend fun open(onEvent: (CxpEvent) -> Unit): Carrier {
        val n = DemoNavigator(store.loadCredential()?.token ?: ByteArray(TOKEN_LEN))
        navigator = n
        return FrameStream(n, onEvent)
    }

    override fun close() {
        navigator?.close()
    }
}

/**
 * A demonstration's store, in memory, with the credential the protocol tests use, which the
 * navigator in memory holds too. Everything it is given ends with the launch.
 */
class DemoStore : Store {
    private var credential: Credential? =
        Credential(1, ByteArray(TOKEN_LEN) { it.toByte() }, "ENTITLEMENT-BLOB".encodeToByteArray())

    override fun loadCredential() = credential

    override fun saveCredential(json: String) {
        credential = parseCredential(json)
    }

    override fun clearCredential() {
        credential = null
    }

    override var profileProductId: Long = 0
}

/**
 * At most [limit] bytes of the file at [url], which a picker or another app handed over. A longer
 * file is refused, as the Android app's readAtMost refuses it, rather than cut into half a
 * document.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun readAtMost(url: NSURL, limit: Int): ByteArray {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        val handle = NSFileHandle.fileHandleForReadingFromURL(url, null) ?: throw UnopenableFileException()
        try {
            val data = handle.readDataUpToLength((limit + 1).convert(), null) ?: throw UnopenableFileException()
            if (data.length > limit.convert()) throw FileTooLargeException(limit)
            return data.toByteArray()
        } finally {
            handle.closeAndReturnError(null)
        }
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

/**
 * The bytes of a file handed to the app, as [readAtMost] reads them, and then the copy made for
 * the app deleted: iOS copies a file another app opens in this one into Documents/Inbox, which
 * backups carry, and the picker copies a pick into the temporary directory.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun takeAtMost(url: NSURL, limit: Int): ByteArray = try {
    readAtMost(url, limit)
} finally {
    if (isCopyForTheApp(url)) NSFileManager.defaultManager.removeItemAtURL(url, null)
}

/** Whether [url] lies in Documents/Inbox or the temporary directory, where iOS puts copies. */
private fun isCopyForTheApp(url: NSURL): Boolean {
    val path = url.URLByResolvingSymlinksInPath?.path ?: return false
    val documents = NSFileManager.defaultManager
        .URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull() as? NSURL
    val inbox = documents?.URLByAppendingPathComponent("Inbox")
    val tmp = NSURL.fileURLWithPath(NSTemporaryDirectory())
    return listOfNotNull(inbox, tmp).mapNotNull { it.URLByResolvingSymlinksInPath?.path }
        .any { path.startsWith(it.trimEnd('/') + "/") }
}

/**
 * The system's document picker, for any file, as the Android app opens any file and sniffs it: a
 * provider's type for a route file is as often wrong as right. A copy is picked, so the grant
 * ends with the pick, and [takeAtMost] deletes the copy once it is read.
 */
class FilePicker {
    // Held while the picker is up: the picker keeps only a weak reference to its delegate.
    private var delegate: Delegate? = null

    fun pick(over: UIViewController, onPicked: (NSURL) -> Unit) {
        // A second tap while the picker is up: UIKit shows one at a time.
        if (over.presentedViewController != null) return
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
        val d = Delegate { url ->
            delegate = null
            url?.let(onPicked)
        }
        delegate = d
        picker.delegate = d
        over.presentViewController(picker, animated = true, completion = null)
    }

    private class Delegate(private val done: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            done(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            done(null)
        }
    }
}
