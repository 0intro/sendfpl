package app.sendfpl

import android.content.Context
import app.sendfpl.cxp.FlightPlanException
import app.sendfpl.cxp.Problem
import app.sendfpl.route.RouteParseException
import java.util.Locale

/**
 * A [Problem] in the pilot's language.
 *
 * The `:cxp` module names a refusal and carries its arguments; the wording is a string resource,
 * so it can be translated. Which refusals are named there and which keep their English is decided
 * in [Problem], and this file only words what it is given.
 */
fun Context.say(problem: Problem): String = when (problem) {
    Problem.RouteTooShort -> getString(R.string.problem_route_too_short)
    is Problem.NotAPosition -> getString(R.string.problem_not_a_position, problem.text)

    // Both coordinate halves take the same four arguments and differ only in the noun, so the
    // resource is picked by which half it is rather than the sentence being built from pieces:
    // a translation has to be free to reorder them.
    is Problem.AmbiguousCoordinate -> getString(
        if (problem.isLongitude) R.string.problem_ambiguous_longitude
        else R.string.problem_ambiguous_latitude,
        problem.text, problem.width, problem.width + 2, problem.width + 3,
    )
    is Problem.UnreadableCoordinate -> getString(
        if (problem.isLongitude) R.string.problem_unreadable_longitude
        else R.string.problem_unreadable_latitude,
        problem.text, problem.width, problem.width + 2, problem.width + 3,
    )
    is Problem.TwoHemispheres -> getString(R.string.problem_two_hemispheres, problem.token)
    is Problem.LatitudeOutOfRange ->
        getString(R.string.problem_latitude_out_of_range, problem.token, degrees(problem.value))
    is Problem.LongitudeOutOfRange ->
        getString(R.string.problem_longitude_out_of_range, problem.token, degrees(problem.value))

    is Problem.WaypointNameTooLong -> getString(
        R.string.problem_waypoint_name_too_long, problem.name, problem.length, problem.max,
    )
    is Problem.WaypointNameNotAlphanumeric ->
        getString(R.string.problem_waypoint_name_not_alphanumeric, problem.name)
    is Problem.AirportNameTooLong -> getString(
        when (problem.end) {
            Problem.AirportNameTooLong.End.DEPARTURE -> R.string.problem_departure_airport_too_long
            Problem.AirportNameTooLong.End.ARRIVAL -> R.string.problem_arrival_airport_too_long
        },
        problem.name, problem.length, problem.device, problem.max,
    )
    is Problem.StatedIdentTooLong -> getString(
        R.string.problem_stated_ident_too_long, problem.name, problem.length, problem.max,
    )
    // One string per kind rather than one with the kind interpolated, because French needs the
    // article and it is not the same one: *le* point de cheminement, *la* voie aerienne.
    is Problem.ElementIdentTooLong -> getString(
        when (problem.kind) {
            Problem.ElementIdentTooLong.Kind.WAYPOINT -> R.string.problem_waypoint_ident_too_long
            Problem.ElementIdentTooLong.Kind.AIRWAY -> R.string.problem_airway_ident_too_long
            Problem.ElementIdentTooLong.Kind.PROCEDURE -> R.string.problem_procedure_ident_too_long
            Problem.ElementIdentTooLong.Kind.TRANSITION -> R.string.problem_transition_ident_too_long
        },
        problem.ident, problem.length, problem.device, problem.max,
    )
    is Problem.RouteTooLong ->
        getString(R.string.problem_route_too_long, problem.bytes, problem.max, problem.device)

    Problem.UnrecognisedFile -> getString(R.string.problem_unrecognised_file)
    Problem.NotXml -> getString(R.string.problem_not_xml)
    Problem.ZipNotRoute -> getString(R.string.problem_zip_not_route)
    Problem.WaypointLibrary -> getString(R.string.problem_waypoint_library)
    Problem.TooFewPoints -> getString(R.string.problem_too_few_points)
    Problem.AllPointsSame -> getString(R.string.problem_all_points_same)
    is Problem.PositionOffEarth ->
        getString(R.string.problem_position_off_earth, degrees(problem.lat), degrees(problem.lon))
    Problem.PositionNotANumber -> getString(R.string.problem_position_not_a_number)
}

/**
 * The [Problem] a throwable carries, or null.
 *
 * Only two exception types carry one, and both are about what the pilot supplied. Everything from
 * the protocol layers is diagnostic and keeps its English, which is what the log sheet is for.
 */
val Throwable.problem: Problem?
    get() = when (this) {
        is RouteParseException -> problem
        is FlightPlanException -> problem
        else -> null
    }

/**
 * A coordinate in a sentence, rather than as a raw double.
 *
 * Six decimal places is the quantum the importers round to and a tenth of a metre on the ground,
 * so nothing is lost, and it keeps `48.791600627042` out of a message a pilot has to read. The
 * locale is the reader's, because in French the decimal mark is a comma and this number is prose
 * rather than a wire value.
 */
private fun degrees(value: Double): String =
    String.format(Locale.getDefault(), "%.6f", value).trimEnd('0').trimEnd('.', ',')
