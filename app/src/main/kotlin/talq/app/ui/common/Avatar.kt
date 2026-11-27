package talq.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import talq.app.theme.AvatarFills

/**
 * True while the account is not checked yet: an avatar (or a picture in a chat) is one the phone has, else the
 * initial (or nothing). Nothing is asked of the server with a password that may be dead (each such request counts as
 * a failed sign-in there).
 */
val LocalPicturesFromPhone = compositionLocalOf { false }

/**
 * A round avatar loaded from the server with the account's credentials. Until the image
 * arrives (or when there is none) it shows the name's initial on a stable colour.
 *
 * @param url an image URL from `TalkClient` (`roomAvatarUrl`, `userAvatarUrl`, ...); empty = initials only
 * @param authorization `TalkClient.authorizationHeader`
 */
@Composable
fun Avatar(
    url: String,
    authorization: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val fill = avatarFill(name)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitial(name),
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (url.isNotEmpty()) {
            AsyncImage(
                model = rememberPicture(url, authorization),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * An image request that carries the account's credentials. Use for every server image.
 * [memoryKey]: an exact memory-cache key (the URL), so another screen can find this copy again;
 * [placeholderKey]: shown from the memory cache while this one loads (see [thumbnailKey]).
 */
fun authorizedImage(
    context: android.content.Context,
    url: String,
    authorization: String,
    memoryKey: String? = null,
    placeholderKey: String? = null,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .httpHeaders(
            NetworkHeaders.Builder()
                .set("Authorization", authorization)
                .set("OCS-APIRequest", "true")
                .build(),
        )
        .apply {
            if (memoryKey != null) memoryCacheKey(memoryKey)
            if (placeholderKey != null) placeholderMemoryCacheKey(placeholderKey)
        }
        .build()

/** [authorizedImage] for a picture on screen: from the phone alone where [LocalPicturesFromPhone] says so. */
@Composable
fun rememberPicture(url: String, authorization: String, memoryKey: String? = null): ImageRequest {
    val context = LocalContext.current
    val fromPhone = LocalPicturesFromPhone.current
    return remember(url, authorization, memoryKey, fromPhone) {
        val image = authorizedImage(context, url, authorization, memoryKey)
        if (fromPhone) image.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build() else image
    }
}

/**
 * The memory-cache key of a picture's thumbnail: its preview URL, set as the exact key by the chat
 * bubble (720) and the shared-files grid (360), so the full-screen viewer can show that copy at
 * once while the sharp one loads.
 */
fun thumbnailKey(previewUrl: String): String = previewUrl

/** The preview size of a picture in a chat bubble (the viewer looks for this copy first). */
const val BUBBLE_PREVIEW_PX = 720

/** The preview size of a picture in the shared-files media grid. */
const val GRID_PREVIEW_PX = 360

/** The stable colour behind [name]'s initial. */
internal fun avatarFill(name: String): Color = AvatarFills[(name.hashCode() and 0x7fffffff) % AvatarFills.size]

/** What stands for [name] while there is no picture. */
internal fun avatarInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val end = trimmed.offsetByCodePoints(0, 1)
    return trimmed.substring(0, end).uppercase()
}
