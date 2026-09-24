package com.tjisok.newsfaces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 12.sp); Text(fmt(value), color = Fg, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.height(28.dp),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line))
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickableNoRipple { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, modifier = Modifier.scale(0.8f), colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}


@Composable
fun Section(title: String, initiallyOpen: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().clickableNoRipple { open = !open }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = Muted)
        }
        if (open) Column(content = content)
    }
}

@Composable
fun Hint(text: String) = Text(text, color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 6.dp))

@Composable
fun MenuContent() {
    val s by app.settings.collectAsStateWithLifecycle()
    val photos by app.photos.collectAsStateWithLifecycle()
    val f = s.face
    val pct = { v: Float -> "${(v * 100).roundToInt()}%" }
    val x2 = { v: Float -> "×%.2f".format(v) }
    LazyColumn(Modifier.fillMaxSize().background(Panel).statusBarsPadding().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(0xFFFF3B30), RoundedCornerShape(5.dp))); Spacer(Modifier.width(8.dp))
                Text("News Faces", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Section("Layout") {
                SliderRow("Columns", s.cols.toFloat(), 1f..16f, { it.roundToInt().toString() }, 14) { v -> app.update { it.copy(cols = v.roundToInt()) } }
                SliderRow("Gap", s.gap, 0f..24f, { "${it.roundToInt()}dp" }) { v -> app.update { it.copy(gap = v) } }
                SliderRow("Corner radius", s.radius, 0f..40f, { "${it.roundToInt()}dp" }) { v -> app.update { it.copy(radius = v) } }
                SliderRow("Tile aspect", s.aspect, 0.5f..2f, { "%.2f".format(it) }) { v -> app.update { it.copy(aspect = v) } }
                Hint("Pinch the grid with two fingers to change the column count. Tap a photo to open it, then pinch without limit.")
            }
        }
        item {
            Section("Colour & sorting") {
                Text("Sort by", color = Muted, fontSize = 12.sp)
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Segmented(listOf("newest" to "Newest", "oldest" to "Oldest", "hue" to "Colour"), s.sort, { v -> app.update { it.copy(sort = v) } })
                    Segmented(listOf("light" to "Light", "sat" to "Vivid", "warm" to "Warm", "source" to "Source"), s.sort, { v -> app.update { it.copy(sort = v) } })
                }
                SwitchRow("Reverse order", s.reverse) { v -> app.update { it.copy(reverse = v) } }
                SliderRow("Hue start", s.hueOff, 0f..360f, { "${it.roundToInt()}°" }) { v -> app.update { it.copy(hueOff = v) } }
                SliderRow("Grey threshold", s.gray, 0f..0.5f, { "%.2f".format(it) }) { v -> app.update { it.copy(gray = v) } }
                SliderRow("Colour strip", s.strip, 0f..14f, { "${it.roundToInt()}dp" }) { v -> app.update { it.copy(strip = v) } }
            }
        }
        item {
            Section("Filters") {
                SliderRow("Max age", s.ageH, 1f..168f, { if (it < 24) "${it.roundToInt()}h" else "%.1fd".format(it / 24).replace(".0d", "d") }) { v -> app.update { it.copy(ageH = v) } }
                SliderRow("Min saturation", s.minSat, 0f..1f, { "%.2f".format(it) }) { v -> app.update { it.copy(minSat = v) } }
                SliderRow("Lightness min", s.lightMin, 0f..1f, { "%.2f".format(it) }) { v -> app.update { it.copy(lightMin = v) } }
                SliderRow("Lightness max", s.lightMax, 0f..1f, { "%.2f".format(it) }) { v -> app.update { it.copy(lightMax = v) } }
                SliderRow("Max photos", s.max.toFloat(), 50f..4000f, { it.roundToInt().toString() }) { v -> app.update { it.copy(max = (v / 50).roundToInt() * 50) } }
                SliderRow("Auto refresh", s.refreshMin.toFloat(), 0f..60f, { if (it < 1) "off" else "${it.roundToInt()} min" }) { v -> app.update { it.copy(refreshMin = v.roundToInt()) }; app.scheduleRefresh() }
            }
        }
        item {
            Section("Face mosaic") {
                Hint("Your face, rebuilt live from the news photos. Each cell shows the photo whose colour is closest to that part of your face. The photos are never altered: the colour sliders shift the target, so different photos get chosen.")
                SliderRow("Resolution", f.cols.toFloat(), 6f..120f, { "${it.roundToInt()} cells" }) { v -> app.updateFace { it.copy(cols = v.roundToInt()) } }
                SliderRow("Face framing", f.pad, 0.8f..3f, x2) { v -> app.updateFace { it.copy(pad = v) } }
                SliderRow("Cell gap", f.gap, 0f..0.5f, pct) { v -> app.updateFace { it.copy(gap = v) } }
                Text("Outline", color = Muted, fontSize = 12.sp)
                Segmented(listOf("head" to "Head shape", "oval" to "Oval", "none" to "Full crop"), f.outline, { v -> app.updateFace { it.copy(outline = v) } })
                Spacer(Modifier.height(6.dp)); Text("Faces", color = Muted, fontSize = 12.sp)
                Segmented(listOf("one" to "Largest face", "all" to "All faces in a grid"), if (f.multi) "all" else "one", { v -> app.updateFace { it.copy(multi = v == "all") } })
                SwitchRow("Mirror", f.mirror) { v -> app.updateFace { it.copy(mirror = v) } }
                SwitchRow("Freeze frame", f.freeze) { v -> app.updateFace { it.copy(freeze = v) } }
                SliderRow("Hue shift", f.hue, -180f..180f, { "${if (it > 0) "+" else ""}${it.roundToInt()}°" }) { v -> app.updateFace { it.copy(hue = v) } }
                SliderRow("Saturation", f.sat, 0f..3f, x2) { v -> app.updateFace { it.copy(sat = v) } }
                SliderRow("Brightness", f.bright, -0.6f..0.6f, { "%+.2f".format(it) }) { v -> app.updateFace { it.copy(bright = v) } }
                SliderRow("Contrast", f.contrast, 0.2f..3f, x2) { v -> app.updateFace { it.copy(contrast = v) } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tint", color = Muted, fontSize = 12.sp)
                    for (c in listOf(0xFFFF7A3D, 0xFFFF2D55, 0xFFAF52DE, 0xFF0A84FF, 0xFF34C759, 0xFFFFCC00, 0xFFFFFFFF)) {
                        val ci = c.toInt()
                        Box(Modifier.size(22.dp).background(Color(ci), RoundedCornerShape(6.dp)).then(if (f.tint == ci) Modifier.padding(0.dp).background(Color.Transparent) else Modifier)
                            .clickableNoRipple { app.updateFace { it.copy(tint = ci) } }) { if (f.tint == ci) Box(Modifier.align(Alignment.Center).size(8.dp).background(Color.Black, RoundedCornerShape(4.dp))) }
                    }
                }
                SliderRow("Tint amount", f.tintAmt, 0f..1f, pct) { v -> app.updateFace { it.copy(tintAmt = v) } }
                Text("Match photos by", color = Muted, fontSize = 12.sp)
                Segmented(listOf("avg" to "Average colour", "vivid" to "Most vivid colour"), f.match, { v -> app.updateFace { it.copy(match = v) } })
                SliderRow("Variety", f.variety, 0f..1f, pct) { v -> app.updateFace { it.copy(variety = v) } }
                SliderRow("Stability", f.stability, 0f..1f, pct) { v -> app.updateFace { it.copy(stability = v) } }
                SliderRow("Frame rate", f.fps.toFloat(), 1f..30f, { "${it.roundToInt()} fps" }) { v -> app.updateFace { it.copy(fps = v.roundToInt()) } }
                Hint("Every head is rendered at the same size. Drag a head to move it on its own, drag empty space to pan, pinch to zoom all heads together. Only photos passing the Filters and Sources are used.")
            }
        }
        item {
            Section("Sources", initiallyOpen = false) {
                val counts = remember(photos) { photos.groupingBy { it.sid }.eachCount() }
                for (pub in Feeds.ALL.map { it.pub }.distinct()) {
                    val list = Feeds.ALL.filter { it.pub == pub }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(list[0].name, color = Fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("all", color = Muted, fontSize = 11.sp, modifier = Modifier.clickableNoRipple { app.update { st -> st.copy(disabledSources = st.disabledSources - list.map { it.id }.toSet()) }; app.refresh() }.padding(4.dp))
                        Text("none", color = Muted, fontSize = 11.sp, modifier = Modifier.clickableNoRipple { app.update { st -> st.copy(disabledSources = st.disabledSources + list.map { it.id }) } }.padding(4.dp))
                    }
                    for (fd in list) {
                        Row(Modifier.fillMaxWidth().clickableNoRipple {
                            val on = s.sourceOn(fd.id)
                            app.update { st -> st.copy(disabledSources = if (on) st.disabledSources + fd.id else st.disabledSources - fd.id) }
                            if (!on) app.fetchFeedAsync(fd)
                        }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = s.sourceOn(fd.id), onCheckedChange = null, modifier = Modifier.size(20.dp), colors = CheckboxDefaults.colors(checkedColor = Accent))
                            Spacer(Modifier.width(10.dp))
                            Text(fd.sec, color = Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text((counts[fd.id] ?: 0).takeIf { it > 0 }?.toString() ?: "", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.refresh() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Refresh now") }
                    OutlinedButton(onClick = { app.clearCache() }) { Text("Clear cache", color = Fg) }
                }
                Hint("Feeds are read directly from each publisher, with their full item lists. Auto refresh keeps topping the cache up.")
            }
        }
    }
}
