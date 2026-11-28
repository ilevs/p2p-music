package io.github.ilevs.p2pmusic.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandGreen = Color(color = 0xFF1DB954)
private val BrandGreenDark = Color(color = 0xFF1AA34A)

private val DarkColors =
    darkColorScheme(
        primary = BrandGreen,
        secondary = BrandGreenDark,
    )

// Material's 10 % focus tint is invisible from a sofa: a TV remote needs to see which row or button it is on.
// Touch never focuses these, so phones and head units look as before.
@OptIn(ExperimentalMaterial3Api::class)
private val RemoteFriendlyRipple =
    RippleConfiguration(
        rippleAlpha =
            RippleAlpha(
                draggedAlpha = 0.16f,
                focusedAlpha = 0.4f,
                hoveredAlpha = 0.08f,
                pressedAlpha = 0.1f,
            ),
    )

private val LightColors =
    lightColorScheme(
        primary = BrandGreenDark,
        secondary = BrandGreen,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2pMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalRippleConfiguration provides RemoteFriendlyRipple, content = content)
    }
}
