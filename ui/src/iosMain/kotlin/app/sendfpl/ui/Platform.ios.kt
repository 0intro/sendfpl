package app.sendfpl.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import app.sendfpl.ui.resources.Res
import app.sendfpl.ui.resources.about_privacy_body_ios
import app.sendfpl.ui.resources.credential_stored_note_ios
import org.jetbrains.compose.resources.StringResource
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp
import platform.Foundation.currentLocale

@Composable
internal actual fun platformColorScheme(dark: Boolean): ColorScheme? = null

/** Empty: on iOS each screen that closes draws its own back arrow. */
@Composable
internal actual fun BackHandler(onBack: () -> Unit) {
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry.withPlainText(text)

/** As Java's `%.Nf` writes it: rounding half up, and no grouping. */
internal actual fun decimal(value: Double, places: Int): String {
    val f = NSNumberFormatter()
    f.numberStyle = NSNumberFormatterDecimalStyle
    f.locale = NSLocale.currentLocale
    f.usesGroupingSeparator = false
    f.roundingMode = NSNumberFormatterRoundHalfUp
    f.minimumFractionDigits = places.toULong()
    f.maximumFractionDigits = places.toULong()
    return f.stringFromNumber(NSNumber(double = value)) ?: value.toString()
}

internal actual val privacyBody: StringResource get() = Res.string.about_privacy_body_ios

internal actual val storedCredentialNote: StringResource get() = Res.string.credential_stored_note_ios
