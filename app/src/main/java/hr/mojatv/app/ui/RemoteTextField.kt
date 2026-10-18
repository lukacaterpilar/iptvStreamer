package hr.mojatv.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import hr.mojatv.app.data.RemoteInput

/**
 * Text field that, while focused, also accepts text sent from a phone via [RemoteInput].
 * Pair it with [RemoteInputOverlay] somewhere on the screen so the user sees the QR code.
 */
@Composable
fun RemoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    /**
     * Done (default) closes the keyboard, Next closes it and moves to the next field (which opens it again),
     * Search is reported to the keyboard as such. Fire TV's keyboard swallows the Back button on fields that
     * advertise Next or the default "form" action, so Next is advertised to the keyboard as Done and the move
     * to the next field is done by the app.
     */
    imeAction: ImeAction = ImeAction.Done,
    /** Called after the keyboard was hidden when its action button (e.g. "Search") is pressed. */
    onImeAction: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val act: KeyboardActionScope.() -> Unit = {
        keyboard?.hide()
        when {
            onImeAction != null -> onImeAction()
            imeAction == ImeAction.Next -> focusManager.moveFocus(FocusDirection.Next)
        }
    }
    DisposableEffect(focused, label) {
        if (focused) RemoteInput.activeLabel = label
        onDispose { if (RemoteInput.activeLabel == label) RemoteInput.activeLabel = null }
    }
    val incoming = RemoteInput.incoming
    LaunchedEffect(incoming) {
        if (focused && incoming != null) onValueChange(incoming.second)
    }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        label = { Text(label) },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (imeAction == ImeAction.Next) ImeAction.Done else imeAction),
        keyboardActions = KeyboardActions(
            onDone = act, onGo = act, onSearch = act, onSend = act, onNext = act,
            // Fire TV's keyboard reports its Back button as "previous": just close the keyboard.
            onPrevious = { keyboard?.hide(); onImeAction?.invoke() },
        ),
    )
}

/** QR code + address for phone typing; renders nothing unless a [RemoteTextField] is focused. */
@Composable
fun RemoteInputOverlay(modifier: Modifier = Modifier) {
    val label = RemoteInput.activeLabel ?: return
    val url = RemoteInput.url ?: return
    val qr = remember(url) { qrBitmap(url, 320) }
    Column(
        modifier.width(230.dp).background(Color(0xF2121C2E), RoundedCornerShape(12.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(S.remoteTitle, color = C.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Image(
            qr.asImageBitmap(), contentDescription = null,
            modifier = Modifier.size(170.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(6.dp),
        )
        Text(url, color = C.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            S.remoteHint(label),
            color = C.dim, fontSize = 11.sp, textAlign = TextAlign.Center,
        )
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
