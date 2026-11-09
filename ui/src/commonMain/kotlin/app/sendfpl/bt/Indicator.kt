package app.sendfpl.bt

/** What a navigator's row, and the home screen's chip, show of it at a glance. */
enum class Indicator {
    /** Nothing worth showing. */
    NONE,

    /** A probe is in flight. */
    CHECKING,

    /** It answered, or a link is already open. */
    LIVE,

    /** It was asked and stayed silent. */
    SILENT,
}
