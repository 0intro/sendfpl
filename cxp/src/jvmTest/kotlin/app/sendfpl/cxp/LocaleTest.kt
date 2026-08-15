package app.sendfpl.cxp

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Digits on the wire are ASCII whatever the JVM's default locale, which only the JVM has. */
class LocaleTest {

    /** A phone set to Arabic or Persian writes other digits through String.format. */
    @Test
    fun `a runway is written in ASCII digits whatever the phone's locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa"))
            assertEquals("26O", normaliseRunway("26"))
            assertEquals("09L", normaliseRunway("9L"))
            assertEquals(
                "FPN/RI:DA:LFPN:R:25R:F:LFPN,N48450E002060",
                buildRoute(Profiles.GPS175, departure = Departure("LFPN", runway = "25R", at = Coordinate(48.75, 2.1))),
            )
        } finally {
            Locale.setDefault(before)
        }
    }
}
