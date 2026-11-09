package app.sendfpl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.sendfpl.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the store listing has to say, readable in the app, with the repository and the issue
 * tracker as links a browser opens. Keep the privacy paragraph in step with `www/privacy.html`.
 * The responsibility paragraph's other copies are listed beside it in strings.xml.
 *
 * A page of its own rather than a sheet, since it is read through rather than acted on. The
 * arrow and the system's Back both close it.
 *
 * A platform that passes [onTryDemo] offers its navigator in memory here, as the iPhone app does
 * for a pilot away from the aircraft and for App Review.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenLink: (String) -> Unit,
    onTryDemo: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.action_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        AboutContent(Modifier.padding(padding), version, onOpenSetup, onOpenLink, onTryDemo)
    }
}

@Composable
private fun AboutContent(
    modifier: Modifier,
    version: String,
    onOpenSetup: () -> Unit,
    onOpenLink: (String) -> Unit,
    onTryDemo: (() -> Unit)?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        // Scrollable: it is longer than a small phone's screen, in French especially.
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.about_version, version),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(Res.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()
        Text(
            stringResource(Res.string.about_responsibility_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(Res.string.about_responsibility_body),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )

        HorizontalDivider()
        Text(
            stringResource(Res.string.about_trademark_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(Res.string.about_trademark_body, CONTACT_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )

        HorizontalDivider()
        Text(
            stringResource(Res.string.about_privacy_title),
            style = MaterialTheme.typography.titleSmall,
        )
        // The route is not listed: it is held in memory only and never written anywhere.
        Text(
            stringResource(privacyBody),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        // Under privacy, because the open source is what backs that paragraph.
        Text(
            stringResource(Res.string.about_licence),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Link(PROJECT_URL, onOpenLink)

        HorizontalDivider()
        Text(stringResource(Res.string.credential_title), style = MaterialTheme.typography.titleSmall)
        // The setup sheet's only entry while a credential is stored, which every shipped build
        // has: the home screen's card shows only without one.
        Text(
            stringResource(Res.string.about_credential_body),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        TextButton(onClick = onOpenSetup) {
            Text(stringResource(Res.string.about_manage_credential))
        }

        if (onTryDemo != null) {
            HorizontalDivider()
            Text(stringResource(Res.string.about_demo_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(Res.string.about_demo_body),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            TextButton(onClick = onTryDemo) {
                Text(stringResource(Res.string.about_try_demo))
            }
        }

        HorizontalDivider()
        Text(
            stringResource(Res.string.about_feedback_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(Res.string.about_feedback_body),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Link(ISSUES_URL, onOpenLink)
    }
}

/**
 * [address] as a link on a line of its own, printed as it would be typed, without its scheme, and
 * opened over https. Not inside a sentence: a link answers taps across the whole box its lines
 * span, and a little above and below it, so one in a paragraph opens from the words around it.
 */
@Composable
private fun Link(address: String, onOpenLink: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val styles = TextLinkStyles(
        style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline),
        // Text has no ripple, and a browser can take a moment to come up.
        pressedStyle = SpanStyle(background = primary.copy(alpha = 0.12f)),
    )
    val url = "https://$address"
    Text(
        buildAnnotatedString {
            withLink(LinkAnnotation.Url(url, styles) { onOpenLink(url) }) { append(address) }
        },
        style = MaterialTheme.typography.bodySmall,
    )
}
