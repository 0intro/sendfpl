package app.sendfpl.cxp

/**
 * A refusal a pilot is expected to act on, named rather than worded so that the application can
 * say it in the pilot's language: this module is plain Kotlin, with no resources. A refusal about
 * a malformed file's internals, such as `an empty :F: element`, has none and keeps its English at
 * the throw site. Every exception carrying a Problem keeps an English message too, for logs and
 * for consumers other than the application.
 */
sealed interface Problem {

    /** Fewer than two identifiers, which is not a navigation. */
    data object RouteTooShort : Problem

    /** A token shaped like a named user waypoint whose second half is not a position. */
    data class NotAPosition(val text: String) : Problem

    /**
     * A decimal point past a degree field of [width] digits, 2 for a latitude and 3 for a
     * longitude, so `00237.2` could be 237.2 degrees or 2 degrees 37.2 minutes.
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

    /** Whole minutes of 60 or more, or more than 600 tenths of a minute. */
    data class MinutesOutOfRange(val text: String, val isLongitude: Boolean) : Problem

    data class LatitudeOutOfRange(val token: String, val value: Double) : Problem
    data class LongitudeOutOfRange(val token: String, val value: Double) : Problem

    data class WaypointNameTooLong(
        val name: String,
        val length: Int,
        val max: Int,
    ) : Problem

    data class WaypointNameNotAlphanumeric(val name: String) : Problem

    /** An identifier with nothing in it, such as a `!` or `=` with no name after it. */
    data object EmptyIdentifier : Problem

    /** An identifier holding a character other than an upper-case letter, a digit or a hyphen. */
    data class BadIdentifier(val ident: String) : Problem

    /**
     * A point named with a hyphen, for a [device] that reads the hyphen as the start of another
     * point. See [Profile.hyphenSplitsPoint].
     */
    data class HyphenInPointName(val name: String, val device: String) : Problem

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
     * An identifier a file stated, too long for this navigator. Unlike a name the importer
     * invented ([WaypointNameTooLong]), it cannot be shortened: a shorter spelling names a
     * different waypoint.
     */
    data class StatedIdentTooLong(
        val name: String,
        val length: Int,
        val max: Int,
    ) : Problem

    /** A route element longer than the handler that reads it back, whose cap depends on [kind]. */
    data class ElementIdentTooLong(
        val kind: Kind,
        val ident: String,
        val length: Int,
        val device: String,
        val max: Int,
    ) : Problem {
        enum class Kind { WAYPOINT, AIRWAY, PROCEDURE, TRANSITION }
    }

    /** An airway with no waypoint before it to join it at: one that starts the route. */
    data class AirwayWithoutEntry(val ident: String) : Problem

    /**
     * An airway with no waypoint after it to leave it at: one that ends the route, or one followed
     * by another airway.
     */
    data class AirwayWithoutExit(val ident: String) : Problem

    data class RouteTooLong(val bytes: Int, val max: Int, val device: String) : Problem

    /** More points than the navigator holds in one flight plan. See [Profile.maxWaypoints]. */
    data class TooManyWaypoints(val points: Int, val max: Int, val device: String) : Problem

    /** Not one of the formats this reads, by extension or by what it begins with. */
    data object UnrecognisedFile : Problem

    /** Five of the six formats are XML, and this file is not. */
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

    /** A position a file states that does not read as one. */
    data class UnreadablePosition(val text: String) : Problem

    /** A file that wrote `NaN` where a coordinate belongs. */
    data object PositionNotANumber : Problem

    /** A `.gfp` approach with no `:AA:` naming its aerodrome, which Garmin's guideline requires. */
    data object ApproachWithoutArrival : Problem

    /** A `.gfp` runway that is not a number from 1 to 36 with an optional location letter. */
    data class NotARunway(val text: String) : Problem

    /** Garmin's `..` between two points of a `.gfp` `:F:` element, a direct leg. */
    data object DirectLeg : Problem
}
