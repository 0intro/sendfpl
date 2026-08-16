package app.sendfpl.cxp

/**
 * A refusal a pilot is expected to act on, named rather than worded.
 *
 * This module has no resources and never will, being a plain Kotlin library with no Android in it,
 * so a message written here can only be written once and only in one language. Naming the refusal
 * and carrying its arguments lets the application say it in the pilot's, the same reason
 * `bt/DeviceStatus.kt` names a state instead of wording one.
 *
 * **The line is what a pilot can act on.** A route that is too short, a file that is not a route,
 * an identifier the navigator cannot read: those are here, because the person reading them is
 * expected to do something about it. A refusal that describes the internals of a malformed file,
 * `an empty :F: element` or `":D:" has nothing after it`, is a diagnostic about a broken export
 * and keeps its English prose at the throw site: it quotes wire syntax anyway, and it reaches a
 * pilot only when a planner has written something wrong.
 *
 * Every exception carrying one keeps a written message too. That is not a duplicate of the
 * translation: it is what a stack trace and a bug report show, and it is what this module says on
 * its own to any consumer that is not the application.
 */
sealed interface Problem {

    // The route box.

    /** Fewer than two identifiers, which is not a navigation. */
    data object RouteTooShort : Problem

    /** A token shaped like a named user waypoint whose second half is not a position. */
    data class NotAPosition(val text: String) : Problem

    /**
     * A decimal point further into a half than its degree field reaches, so `00237.2` could be
     * 237.2 degrees or 2 degrees 37.2 minutes. [width] is how many digits the degree field holds,
     * 2 for a latitude and 3 for a longitude.
     */
    data class AmbiguousCoordinate(
        val text: String,
        val isLongitude: Boolean,
        val width: Int,
    ) : Problem

    /** Digits that are not decimal degrees, whole minutes or tenths of a minute. */
    data class UnreadableCoordinate(
        val text: String,
        val isLongitude: Boolean,
        val width: Int,
    ) : Problem

    /** `N48N492E002372`, a hemisphere letter at both ends of one half. */
    data class TwoHemispheres(val token: String) : Problem

    data class LatitudeOutOfRange(val token: String, val value: Double) : Problem
    data class LongitudeOutOfRange(val token: String, val value: Double) : Problem

    // Identifiers the navigator cannot read. See [UnknownDeviceException] for why exceeding a cap
    // is fatal rather than something to trim.

    data class WaypointNameTooLong(
        val name: String,
        val length: Int,
        val max: Int,
    ) : Problem

    data class WaypointNameNotAlphanumeric(val name: String) : Problem

    /** An aerodrome identifier longer than the `:DA:`/`:AA:` handler reads. */
    data class AirportNameTooLong(
        val end: End,
        val name: String,
        val length: Int,
        val device: String,
        val max: Int,
    ) : Problem {
        enum class End { DEPARTURE, ARRIVAL }
    }

    /**
     * An identifier a file stated, too long for this navigator.
     *
     * Distinct from [WaypointNameTooLong] because the remedy differs: a name this importer
     * invented can be shortened, and one the file asserts a database knows cannot, since a shorter
     * spelling of it names a different waypoint.
     */
    data class StatedIdentTooLong(
        val name: String,
        val length: Int,
        val max: Int,
    ) : Problem

    /**
     * A route element longer than the handler that reads it back.
     *
     * Every element has its own cap and they are not the same number, so the kind has to travel
     * with the length: a six character name is fine for a procedure and one too many for a
     * waypoint. [AirportNameTooLong] stays separate because an aerodrome is worth naming as one in
     * the message rather than calling it an element.
     */
    data class ElementIdentTooLong(
        val kind: Kind,
        val ident: String,
        val length: Int,
        val device: String,
        val max: Int,
    ) : Problem {
        enum class Kind { WAYPOINT, AIRWAY, PROCEDURE, TRANSITION }
    }

    data class RouteTooLong(val bytes: Int, val max: Int, val device: String) : Problem

    // Files.

    /** Not one of the formats this reads, by extension or by what it begins with. */
    data object UnrecognisedFile : Problem

    /** Four of the six formats are XML, and this file is not. */
    data object NotXml : Problem

    /** A KMZ, which is a zip holding a KML. */
    data object ZipNotRoute : Problem

    /** A SkyDemon waypoint library: a set of places with no order to fly them in. */
    data object WaypointLibrary : Problem

    /** A file that parsed but holds fewer than two points. */
    data object TooFewPoints : Problem

    /** Every point at the same place, so there is nothing to fly. */
    data object AllPointsSame : Problem

    data class PositionOffEarth(val lat: Double, val lon: Double) : Problem

    /** A file that wrote `NaN` where a coordinate belongs. */
    data object PositionNotANumber : Problem
}
