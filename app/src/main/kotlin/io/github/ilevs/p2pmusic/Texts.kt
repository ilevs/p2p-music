package io.github.ilevs.p2pmusic

import androidx.annotation.StringRes

/**
 * User-facing text for code without a Context (engines, trackers, controllers): [AppGraph] points [lookup] at the
 * app's resources, so messages follow the system language. Unit tests point it at the English strings.xml.
 */
object Texts {
    @Volatile
    var lookup: (Int, Array<out Any>) -> String = { id, args -> "#$id ${args.joinToString()}" }
}

fun text(
    @StringRes id: Int,
    vararg args: Any,
): String = Texts.lookup(id, args)
