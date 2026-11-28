package io.github.ilevs.p2pmusic.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * A single-line text field a TV remote can pass through: up and down move the focus on, OK opens the keyboard.
 * Compose does this itself only for keys of a hardware D-pad, not for keys sent by the system or by adb.
 */
internal fun Modifier.remoteKeys(
    focus: FocusManager,
    keyboard: SoftwareKeyboardController?,
): Modifier =
    onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
            when (event.key) {
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                Key.DirectionCenter -> keyboard?.run { show() } != null // consumed when there is a keyboard to show
                else -> false
            }
    }

/** The keyboard opens at a tap or at OK, not when a remote merely passes the field. A tap shows it as before. */
internal fun KeyboardOptions.onTapOnly(): KeyboardOptions = copy(showKeyboardOnFocus = false)
