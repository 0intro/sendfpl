package app.sendfpl

import android.content.Context
import app.sendfpl.cxp.FlightPlanException
import app.sendfpl.cxp.Problem
import app.sendfpl.route.RouteParseException
import java.util.Locale

/**
 * A [Problem] in the pilot's language: `:cxp` names a refusal and carries its arguments, and the
 * wording is a string resource, so it can be translated.
 */
fun Context.say(problem: Problem): String = when (problem) {
    Problem.RouteTooShort -> getString(R.string.problem_route_too_short)
    is Problem.NotAPosition -> getString(R.string.problem_not_a_position, problem.text)

    // A resource per coordinate half rather than a sentence built from pieces, so a translation
    // is free to reorder them.
    is Problem.AmbiguousCoordinate -> getString(
        if (problem.isLongitude) R.string.problem_ambiguous_longitude
        else R.string.problem_ambiguous_latitude,
        problem.text, problem.width, problem.width + 2, problem.width + 3,
    )
    is Problem.UnreadableCoordinate -> getString(
        if (problem.isLongitude) R.string.problem_unreadable_longitude
        else R.string.problem_unreadable_latitude,
        problem.text, problem.width + 2, problem.width + 3,
    )
    is Problem.MinutesOutOfRange -> getString(
        if (problem.isLongitude) R.string.problem_minutes_out_of_range_longitude
        else R.string.problem_minutes_out_of_range_latitude,
        problem.text,
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
    Problem.EmptyIdentifier -> getString(R.string.problem_empty_identifier)
    is Problem.BadIdentifier -> getString(R.string.problem_bad_identifier, problem.ident)
    is Problem.HyphenInPointName ->
        getString(R.string.problem_hyphen_in_point, problem.name, problem.device)
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
    // One string per kind rather than the kind interpolated: French needs a different article,
    // le point de cheminement, la voie aerienne.
    is Problem.ElementIdentTooLong -> getString(
        when (problem.kind) {
            Problem.ElementIdentTooLong.Kind.WAYPOINT -> R.string.problem_waypoint_ident_too_long
            Problem.ElementIdentTooLong.Kind.AIRWAY -> R.string.problem_airway_ident_too_long
            Problem.ElementIdentTooLong.Kind.PROCEDURE -> R.string.problem_procedure_ident_too_long
            Problem.ElementIdentTooLong.Kind.TRANSITION -> R.string.problem_transition_ident_too_long
        },
        problem.ident, problem.length, problem.device, problem.max,
    )
    is Problem.AirwayWithoutEntry -> getString(R.string.problem_airway_without_entry, problem.ident)
    is Problem.AirwayWithoutExit -> getString(R.string.problem_airway_without_exit, problem.ident)
    is Problem.RouteTooLong ->
        getString(R.string.problem_route_too_long, problem.bytes, problem.max, problem.device)
    is Problem.TooManyWaypoints ->
        getString(R.string.problem_too_many_waypoints, problem.points, problem.max, problem.device)

    Problem.UnrecognisedFile -> getString(R.string.problem_unrecognised_file)
    Problem.NotXml -> getString(R.string.problem_not_xml)
    Problem.ZipNotRoute -> getString(R.string.problem_zip_not_route)
    Problem.WaypointLibrary -> getString(R.string.problem_waypoint_library)
    Problem.TooFewPoints -> getString(R.string.problem_too_few_points)
    Problem.AllPointsSame -> getString(R.string.problem_all_points_same)
    is Problem.PositionOffEarth ->
        getString(R.string.problem_position_off_earth, degrees(problem.lat), degrees(problem.lon))
    Problem.PositionNotANumber -> getString(R.string.problem_position_not_a_number)
    is Problem.UnreadablePosition -> getString(R.string.problem_unreadable_position, problem.text)
    Problem.ApproachWithoutArrival -> getString(R.string.problem_approach_without_arrival)
    is Problem.NotARunway -> getString(R.string.problem_not_a_runway, problem.text)
    Problem.DirectLeg -> getString(R.string.problem_direct_leg)
}

/**
 * The [Problem] a throwable carries, or null. Only the two exceptions about what the pilot
 * supplied carry one.
 */
val Throwable.problem: Problem?
    get() = when (this) {
        is RouteParseException -> problem
        is FlightPlanException -> problem
        else -> null
    }

/**
 * A coordinate for a sentence: six decimal places, the quantum the importers round to and a tenth
 * of a metre, in the reader's locale, since this is prose rather than a wire value.
 */
private fun degrees(value: Double): String =
    String.format(Locale.getDefault(), "%.6f", value).trimEnd('0').trimEnd('.', ',')
