package ua.ledpanel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private val Background = Color(0xFF0B0C10)
private val TextMain = Color(0xFFECEDEF)
private val TextDim = Color(0xFF8A8F98)
private val Ok = Color(0xFF3DDC84)
private val WarmWhite = Color(0xFFFFF1D6)

/** Готові кольори: null — білий канал стрічки, решта — відтінок 0..360. */
private val presets: List<Float?> = listOf(null, 0f, 25f, 50f, 120f, 185f, 225f, 285f)
private const val MAX_TEMPLATES = 6

private fun hueColor(hue: Float?) = if (hue == null) WarmWhite else Color.hsv(hue, 1f, 1f)

private fun sameHue(a: Float?, b: Float?) = if (a == null || b == null) a == b else abs(a - b) < 1f

private fun sameLight(a: Light, b: Light) = sameHue(a.hue, b.hue) && abs(a.brightness - b.brightness) < 0.01f

@Composable
fun PanelScreen(
    status: LedController.Status,
    light: Light,
    hasPermissions: Boolean,
    templates: List<Light>,
    onChange: (Light) -> Unit,
    onTemplatesChange: (List<Light>) -> Unit,
    onGrant: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val ready = hasPermissions && status == LedController.Status.Connected
    val canAdjust = ready && light.on

    Box(Modifier.fillMaxSize().background(Background)) {
        CarBackdrop()

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusLine(status, hasPermissions, onGrant, onEnableBluetooth)

            Spacer(Modifier.weight(1f))
            PowerButton(light, enabled = ready) { onChange(light.copy(on = !light.on)) }
            Spacer(Modifier.weight(1f))

            Templates(
                templates = templates,
                current = light,
                enabled = ready,
                onApply = { onChange(it.copy(on = true)) },
                onChange = onTemplatesChange,
            )

            Column(Modifier.alpha(if (canAdjust) 1f else 0.35f)) {
                Label("Колір")
                presets.chunked(4).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        row.forEach { hue ->
                            Box(
                                Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(hueColor(hue))
                                    .border(if (sameHue(hue, light.hue)) 4.dp else 0.dp, TextMain, CircleShape)
                                    .clickable(enabled = canAdjust) { onChange(light.copy(hue = hue)) },
                            )
                        }
                    }
                }
                GradientSlider(
                    value = (light.hue ?: 0f) / 360f,
                    enabled = canAdjust,
                    brush = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }),
                    thumbColor = hueColor(light.hue),
                ) { onChange(light.copy(hue = it * 359f)) }

                Label("Яскравість  ${(light.brightness * 100).roundToInt()}%")
                GradientSlider(
                    value = (light.brightness - 0.05f) / 0.95f,
                    enabled = canAdjust,
                    brush = Brush.horizontalGradient(listOf(Color(0xFF1C1D22), hueColor(light.hue))),
                    thumbColor = hueColor(light.hue),
                ) { onChange(light.copy(brightness = 0.05f + it * 0.95f)) }
            }
            Text(
                "Фото: Thomas Doerfer, CC BY-SA 3.0, Wikimedia Commons",
                color = TextDim.copy(alpha = 0.5f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Фото «дев'ятки» вгорі екрана, притемнене й розчинене в тлі. */
@Composable
private fun CarBackdrop() {
    Box(Modifier.fillMaxWidth().height(380.dp)) {
        Image(
            painter = painterResource(R.drawable.car),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.45f),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Background.copy(alpha = 0.6f),
                        0.45f to Color.Transparent,
                        1f to Background,
                    ),
                ),
        )
    }
}

/**
 * Шаблони: торкання — увімкнути, утримання — видалити, «+» — зберегти поточні колір і яскравість.
 */
@Composable
private fun Templates(
    templates: List<Light>,
    current: Light,
    enabled: Boolean,
    onApply: (Light) -> Unit,
    onChange: (List<Light>) -> Unit,
) {
    var toDelete by remember { mutableStateOf<Light?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Label(if (templates.isEmpty()) "Шаблони — натисніть «+», щоб зберегти поточний" else "Шаблони")
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            templates.forEach { t ->
                val selected = current.on && sameLight(t, current)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(hueColor(t.hue).copy(alpha = 0.3f + 0.7f * t.brightness))
                        .border(if (selected) 3.dp else 0.dp, TextMain, RoundedCornerShape(14.dp))
                        .alpha(if (enabled) 1f else 0.5f)
                        .combinedClickable(
                            onClick = { if (enabled) onApply(t) },
                            onLongClick = { toDelete = t },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${(t.brightness * 100).roundToInt()}",
                        color = Color.Black.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val canAdd = templates.size < MAX_TEMPLATES && templates.none { sameLight(it, current) }
            if (canAdd) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(2.dp, TextDim, RoundedCornerShape(14.dp))
                        .clickable { onChange(templates + current.copy(on = true)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = TextMain, fontSize = 26.sp)
                }
            }
        }
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Видалити шаблон?") },
            confirmButton = {
                TextButton(onClick = {
                    onChange(templates.filterNot { sameLight(it, t) })
                    toDelete = null
                }) { Text("Видалити") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Ні") } },
        )
    }
}

@Composable
private fun StatusLine(
    status: LedController.Status,
    hasPermissions: Boolean,
    onGrant: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val (text, color) = when {
        !hasPermissions -> "Потрібен дозвіл на Bluetooth" to TextDim
        status == LedController.Status.BluetoothOff -> "Bluetooth вимкнено" to TextDim
        status == LedController.Status.Searching -> "Шукаю підсвітку…" to TextDim
        status == LedController.Status.Connecting -> "Підключаюсь…" to TextDim
        else -> "● Підключено" to Ok
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = color, fontSize = 18.sp)
        val action = when {
            !hasPermissions -> "Дозволити" to onGrant
            status == LedController.Status.BluetoothOff -> "Увімкнути Bluetooth" to onEnableBluetooth
            else -> null
        }
        action?.let { (label, onClick) ->
            Button(onClick = onClick, modifier = Modifier.padding(top = 12.dp)) { Text(label, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun PowerButton(light: Light, enabled: Boolean, onToggle: () -> Unit) {
    val lit = hueColor(light.hue).copy(alpha = 0.35f + 0.65f * light.brightness)
    Box(
        Modifier
            .size(150.dp)
            .shadow(if (light.on) 32.dp else 0.dp, CircleShape, ambientColor = lit, spotColor = lit)
            .clip(CircleShape)
            .background(if (light.on) lit else Color(0xFF1C1D22))
            .clickable(enabled = enabled, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (light.on) "Вимкнути" else "Увімкнути",
            color = if (light.on) Color.Black.copy(alpha = 0.75f) else TextMain,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

/** Великий повзунок під палець: доторк або протягування в будь-якому місці смуги. */
@Composable
private fun GradientSlider(
    value: Float,
    enabled: Boolean,
    brush: Brush,
    thumbColor: Color,
    onValueChange: (Float) -> Unit,
) {
    val onChange by rememberUpdatedState(onValueChange)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onChange((down.position.x / size.width).coerceIn(0f, 1f))
                    drag(down.id) { change ->
                        onChange((change.position.x / size.width).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
            },
    ) {
        val thumb = 38.dp
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(brush),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (maxWidth - thumb) * value.coerceIn(0f, 1f))
                .size(thumb)
                .shadow(4.dp, CircleShape)
                .background(thumbColor, CircleShape)
                .border(3.dp, Color.White, CircleShape),
        )
    }
}
