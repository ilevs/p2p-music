package io.github.ilevs.p2pmusic.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val coverClient = OkHttpClient()

/** Album art from [url]; a blank square while it loads or when there is none. */
@Composable
fun Cover(
    url: String,
    modifier: Modifier = Modifier,
) {
    val image by produceState<ImageBitmap?>(null, url) {
        // shortcut: no cache, so rows load their art again when scrolled back; add a disk cache if that gets slow.
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    coverClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        response.body
                            ?.takeIf { response.isSuccessful }
                            ?.let { BitmapFactory.decodeStream(it.byteStream())?.asImageBitmap() }
                    }
                }.getOrNull()
            }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
