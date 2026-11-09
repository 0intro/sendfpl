package app.sendfpl.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry
import org.jetbrains.compose.resources.StringResource

/** The platform's own colours, Material You on Android 12 and newer, or null for the app's. */
@Composable
internal expect fun platformColorScheme(dark: Boolean): ColorScheme?

/** The system's Back, where there is one. */
@Composable
internal expect fun BackHandler(onBack: () -> Unit)

/** [text] as a clip, under [label] where the platform shows one. */
internal expect fun plainTextClip(label: String, text: String): ClipEntry

/** [value] with [places] decimals, in the reader's locale. */
internal expect fun decimal(value: Double, places: Int): String

/** The About page's privacy paragraph: what this platform's app keeps, and where. */
internal expect val privacyBody: StringResource

/** What the setup sheet says of a stored credential on this platform. */
internal expect val storedCredentialNote: StringResource
