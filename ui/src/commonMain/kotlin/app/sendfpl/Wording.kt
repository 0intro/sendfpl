package app.sendfpl

import androidx.compose.runtime.Composable

/**
 * Words for the pilot, chosen when they are shown rather than when they are made: the state
 * outlives a change of language, and a sentence worded beforehand would stay in the old one.
 */
class Wording(val words: @Composable () -> String)

/** The outcome of the last send, so the UI can say something specific. */
sealed interface Outcome {
    data object Success : Outcome
    data class Failure(val message: Wording, val step: Step?) : Outcome
}
