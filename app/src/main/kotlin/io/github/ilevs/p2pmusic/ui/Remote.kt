package io.github.ilevs.p2pmusic.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Up and down move the focus out of a single-line text field, so a TV remote can leave it. Compose does this itself
 * only for keys of a hardware D-pad, not for keys sent by the system or by adb.
 */
internal fun Modifier.leaveOnUpDown(focus: FocusManager): Modifier =
    onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
            when (event.key) {
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                else -> false
            }
    }
