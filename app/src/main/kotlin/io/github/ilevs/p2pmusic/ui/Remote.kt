package io.github.ilevs.p2pmusic.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * A single-line text field a TV remote can pass through: up and down move the focus on, OK opens the keyboard.
 * Compose moves focus itself only for keys of a hardware D-pad, and opens the keyboard on every focus, which then
 * takes the remote's keys. Reached by keys, the field closes it again; a tap still opens it as before.
 */
internal fun Modifier.remoteKeys(
    focus: FocusManager,
    keyboard: SoftwareKeyboardController?,
    input: InputModeManager,
): Modifier =
    onFocusChanged { if (it.isFocused && input.inputMode == InputMode.Keyboard) keyboard?.hide() }
        .onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown &&
                when (event.key) {
                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                    Key.DirectionCenter -> keyboard?.run { show() } != null
                    else -> false
                }
        }
