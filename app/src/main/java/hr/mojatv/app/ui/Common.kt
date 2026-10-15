package hr.mojatv.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import hr.mojatv.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object C {
    val bg = Color(0xFF0B1220)
    // Panels are translucent so the space backdrop shows through.
    val panel = Color(0xC4121C2E)
    val cell = Color(0xB31A2740)
    val cellNow = Color(0xCC213456)
    val accent = Color(0xFF3D8BFF)
    val text = Color(0xFFE8EEF8)
    val dim = Color(0xFF9AA9C4)
    val fav = Color(0xFFFFC94D)
}

@Composable
fun MojaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, background = C.bg, surface = C.panel,
            onBackground = C.text, onSurface = C.text,
        ),
        content = content,
    )
}

/** Background pictures offered in settings: key -> drawable (0 = no picture). Names come from Strings.backgroundNames. */
val BACKGROUNDS: List<Pair<String, Int>> = listOf(
    "space" to R.drawable.space_bg,
    "abstract" to R.drawable.abstract_bg,
    "autumn" to R.drawable.autumn_bg,
    "droplets" to R.drawable.droplets_bg,
    "none" to 0,
)

/** Runtime look settings that every screen observes. */
object AppTheme {
    var background by mutableStateOf("space")
}

/** Full-screen backdrop (picture chosen in settings, dimmed so text stays readable) or plain colour. */
@Composable
fun SpaceBackground(content: @Composable BoxScope.() -> Unit) {
    val res = BACKGROUNDS.firstOrNull { it.first == AppTheme.background }?.second ?: R.drawable.space_bg
    Box(Modifier.fillMaxSize().background(C.bg)) {
        if (res != 0) {
            Image(
                painterResource(res), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color(0x8C060A14)))
        }
        content()
    }
}

/**
 * D-pad friendly clickable box: highlights when focused, OK = click, long OK = long click.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(6.dp),
    color: Color = C.cell,
    focusedColor: Color = C.accent.copy(alpha = 0.35f),
    selected: Boolean = false,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clip(shape)
            .background(
                when {
                    focused -> focusedColor
                    selected -> C.accent.copy(alpha = 0.18f)
                    else -> color
                }
            )
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) { content(focused) }
}

@Composable
fun Loading(text: String = S.loading) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(40.dp), color = C.accent)
            Text(text, color = C.dim)
        }
    }
}

@Composable
fun ErrorText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color(0xFFFF8A80))
    }
}

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
fun hhmm(epochSec: Long): String = hm.format(Date(epochSec * 1000))

fun formatMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
