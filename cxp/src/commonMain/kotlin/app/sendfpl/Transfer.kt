package app.sendfpl

import app.sendfpl.cxp.Profile
import app.sendfpl.route.ParsedRoute

/** What [transfer] sent: the route string, and the model whose limits it was built for. */
data class Sent(val route: String, val profile: Profile)

/**
 * A whole transfer of [route], as the apps make it: authenticate, read the capabilities and the
 * model the navigator names, build the route for that model, or for [picked] when it names none
 * with limits here, and upload it. The route is built only once the model is known, because models
 * differ in what a user waypoint is sent as. [onModel] hears the model before the route is built,
 * and [onRoute] the route before it goes up.
 */
fun ConnextClient.transfer(
    route: ParsedRoute,
    picked: Profile,
    onModel: (identified: Profile?, effective: Profile) -> Unit = { _, _ -> },
    onRoute: (String) -> Unit = {},
): Sent {
    connectAndAuthenticate()
    val caps = negotiateCapabilities()
    awaitProductData()
    val identified = productData?.profile
    val effective = identified ?: picked
    onModel(identified, effective)
    val text = route.render(effective, caps)
    onRoute(text)
    upload(text, effective, caps)
    return Sent(text, effective)
}

/**
 * The largest route file an app reads into memory. The picker filters nothing, and an unbounded
 * read of a mis-picked video runs out of memory: a route string is at most 3520 bytes.
 */
const val ROUTE_FILE_LIMIT = 4 * 1024 * 1024

/**
 * The largest credential file an app reads. A picker accepts any type, since a file manager may
 * type a `.json` as something else, and a credential is a few hundred bytes.
 */
const val CREDENTIAL_FILE_LIMIT = 64 * 1024

/**
 * A file far larger than anything here reads. Carries the bound, so an app can word the message
 * in the pilot's language.
 */
class FileTooLargeException(val limit: Int) : IllegalArgumentException(
    "that file is bigger than ${humanLimit(limit)}, which is far more than this needs. " +
        "It is probably not the file you meant to pick"
)

/**
 * A byte count as a pilot would read it, so a limit of 64 KiB does not print as "0 MB". In
 * English units unless the caller words them, as a screen does from its resources.
 */
fun humanLimit(
    bytes: Int,
    megabytes: (Int) -> String = { "$it MB" },
    kilobytes: (Int) -> String = { "$it kB" },
): String =
    if (bytes >= 1024 * 1024) megabytes(bytes / (1024 * 1024)) else kilobytes(bytes / 1024)
