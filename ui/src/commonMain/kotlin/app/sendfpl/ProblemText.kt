package app.sendfpl

import androidx.compose.runtime.Composable
import app.sendfpl.cxp.FlightPlanException
import app.sendfpl.cxp.Problem
import app.sendfpl.route.RouteParseException
import app.sendfpl.ui.decimal
import app.sendfpl.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A [Problem] in the pilot's language: `:cxp` names a refusal and carries its arguments, and the
 * wording is a string resource, so it can be translated.
 */
@Composable
fun Problem.text(): String = when (val problem = this) {
    Problem.RouteTooShort -> stringResource(Res.string.problem_route_too_short)
    is Problem.NotAPosition -> stringResource(Res.string.problem_not_a_position, problem.text)

    // A resource per coordinate half rather than a sentence built from pieces, so a translation
    // is free to reorder them.
    is Problem.AmbiguousCoordinate -> stringResource(
        if (problem.isLongitude) Res.string.problem_ambiguous_longitude
        else Res.string.problem_ambiguous_latitude,
        problem.text, problem.width, problem.width + 2, problem.width + 3,
    )
    is Problem.UnreadableCoordinate -> stringResource(
        if (problem.isLongitude) Res.string.problem_unreadable_longitude
        else Res.string.problem_unreadable_latitude,
        problem.text, problem.width + 2, problem.width + 3,
    )
    is Problem.MinutesOutOfRange -> stringResource(
        if (problem.isLongitude) Res.string.problem_minutes_out_of_range_longitude
        else Res.string.problem_minutes_out_of_range_latitude,
        problem.text,
    )
    is Problem.TwoHemispheres -> stringResource(Res.string.problem_two_hemispheres, problem.token)
    is Problem.LatitudeOutOfRange ->
        stringResource(Res.string.problem_latitude_out_of_range, problem.token, degrees(problem.value))
    is Problem.LongitudeOutOfRange ->
        stringResource(Res.string.problem_longitude_out_of_range, problem.token, degrees(problem.value))

    is Problem.WaypointNameTooLong -> stringResource(
        Res.string.problem_waypoint_name_too_long, problem.name, problem.length, problem.max,
    )
    is Problem.WaypointNameNotAlphanumeric ->
        stringResource(Res.string.problem_waypoint_name_not_alphanumeric, problem.name)
    Problem.EmptyIdentifier -> stringResource(Res.string.problem_empty_identifier)
    is Problem.BadIdentifier -> stringResource(Res.string.problem_bad_identifier, problem.ident)
    is Problem.HyphenInPointName ->
        stringResource(Res.string.problem_hyphen_in_point, problem.name, problem.device)
    is Problem.AirportNameTooLong -> stringResource(
        when (problem.end) {
            Problem.AirportNameTooLong.End.DEPARTURE -> Res.string.problem_departure_airport_too_long
            Problem.AirportNameTooLong.End.ARRIVAL -> Res.string.problem_arrival_airport_too_long
        },
        problem.name, problem.length, problem.device, problem.max,
    )
    is Problem.StatedIdentTooLong -> stringResource(
        Res.string.problem_stated_ident_too_long, problem.name, problem.length, problem.max,
    )
    // One string per kind rather than the kind interpolated: French needs a different article,
    // le point de cheminement, la voie aerienne.
    is Problem.ElementIdentTooLong -> stringResource(
        when (problem.kind) {
            Problem.ElementIdentTooLong.Kind.WAYPOINT -> Res.string.problem_waypoint_ident_too_long
            Problem.ElementIdentTooLong.Kind.AIRWAY -> Res.string.problem_airway_ident_too_long
            Problem.ElementIdentTooLong.Kind.PROCEDURE -> Res.string.problem_procedure_ident_too_long
            Problem.ElementIdentTooLong.Kind.TRANSITION -> Res.string.problem_transition_ident_too_long
        },
        problem.ident, problem.length, problem.device, problem.max,
    )
    is Problem.AirwayWithoutEntry -> stringResource(Res.string.problem_airway_without_entry, problem.ident)
    is Problem.AirwayWithoutExit -> stringResource(Res.string.problem_airway_without_exit, problem.ident)
    is Problem.RouteTooLong ->
        stringResource(Res.string.problem_route_too_long, problem.bytes, problem.max, problem.device)
    is Problem.TooManyWaypoints ->
        stringResource(Res.string.problem_too_many_waypoints, problem.points, problem.max, problem.device)

    Problem.UnrecognisedFile -> stringResource(Res.string.problem_unrecognised_file)
    Problem.NotXml -> stringResource(Res.string.problem_not_xml)
    is Problem.UnreadableEncoding ->
        stringResource(Res.string.problem_unreadable_encoding, problem.encoding)
    Problem.ZipNotRoute -> stringResource(Res.string.problem_zip_not_route)
    Problem.WaypointLibrary -> stringResource(Res.string.problem_waypoint_library)
    Problem.TooFewPoints -> stringResource(Res.string.problem_too_few_points)
    Problem.AllPointsSame -> stringResource(Res.string.problem_all_points_same)
    is Problem.PositionOffEarth ->
        stringResource(Res.string.problem_position_off_earth, degrees(problem.lat), degrees(problem.lon))
    Problem.PositionNotANumber -> stringResource(Res.string.problem_position_not_a_number)
    is Problem.UnreadablePosition -> stringResource(Res.string.problem_unreadable_position, problem.text)
    Problem.ApproachWithoutArrival -> stringResource(Res.string.problem_approach_without_arrival)
    is Problem.NotARunway -> stringResource(Res.string.problem_not_a_runway, problem.text)
    Problem.DirectLeg -> stringResource(Res.string.problem_direct_leg)
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
private fun degrees(value: Double): String = decimal(value, 6).trimEnd('0').trimEnd('.', ',')
