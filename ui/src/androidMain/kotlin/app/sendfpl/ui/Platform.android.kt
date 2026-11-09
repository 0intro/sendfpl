package app.sendfpl.ui

import android.content.ClipData
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import app.sendfpl.ui.resources.Res
import app.sendfpl.ui.resources.about_privacy_body
import app.sendfpl.ui.resources.credential_stored_note
import java.util.Locale
import org.jetbrains.compose.resources.StringResource

@Composable
internal actual fun platformColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
internal actual fun BackHandler(onBack: () -> Unit) = androidx.activity.compose.BackHandler(onBack = onBack)

internal actual fun plainTextClip(label: String, text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText(label, text))

internal actual fun decimal(value: Double, places: Int): String =
    String.format(Locale.getDefault(), "%.${places}f", value)

internal actual val privacyBody: StringResource get() = Res.string.about_privacy_body

internal actual val storedCredentialNote: StringResource get() = Res.string.credential_stored_note
