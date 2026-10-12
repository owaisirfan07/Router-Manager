package com.veyronmonitor.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val ACTIVE_THRESHOLD_W = 10.0

private val NODE_W = 96.dp
private val NODE_H = 100.dp
private val TILE = 54.dp
private val PAD = 4.dp
private val DIAGRAM_H = 290.dp

private fun watts(w: Double): String = when {
    w >= 1000 -> "%.2f kW".format(w / 1000)
    else -> "${w.toInt()} W"
}

/**
 * Live energy flow: Solar and Grid at the top, Battery and Home at the
 * bottom, the inverter in the middle. A connection glows and carries
 * moving dots only while power is really flowing on it; the dots move
 * faster when more power flows.
 */
@Composable
fun EnergyFlowDiagram(
    solarWatts: Double,
    batteryPercent: Int?,
    batteryChargingWatts: Double,
    batteryDischargingWatts: Double,
    gridWatts: Double,
    houseWatts: Double
) {
    val t = rememberInfiniteTransition(label = "flow")
    val phase by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "phase"
    )
    val pulse by t.animateFloat(
        0.35f, 0.75f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse"
    )

    val solarOn = solarWatts > ACTIVE_THRESHOLD_W
    val chargeOn = batteryChargingWatts > ACTIVE_THRESHOLD_W
    val dischargeOn = !chargeOn && batteryDischargingWatts > ACTIVE_THRESHOLD_W
    val gridOn = gridWatts > ACTIVE_THRESHOLD_W
    val houseOn = houseWatts > ACTIVE_THRESHOLD_W
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    Box(Modifier.fillMaxWidth().height(DIAGRAM_H).padding(horizontal = 6.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val pad = PAD.toPx()
            val topY = pad + TILE.toPx() / 2
            val botY = h - pad - NODE_H.toPx() + TILE.toPx() / 2
            val leftX = pad + NODE_W.toPx() / 2
            val rightX = w - pad - NODE_W.toPx() / 2
            val center = Offset(w / 2, h / 2 - 6.dp.toPx())
            val stroke = 3.dp.toPx()

            // "bus" style route: out of the tile's inner side, across, then into the hub
            // (never crosses the node labels).
            val tileHalf = TILE.toPx() / 2
            val hubHalf = 29.dp.toPx()
            val lane = 14.dp.toPx()
            fun route(node: Offset): List<Offset> {
                val left = node.x < center.x
                val top = node.y < center.y
                val start = Offset(if (left) node.x + tileHalf else node.x - tileHalf, node.y)
                val laneX = if (left) center.x - lane else center.x + lane
                val end = Offset(laneX, if (top) center.y - hubHalf else center.y + hubHalf)
                return listOf(start, Offset(laneX, node.y), end)
            }

            fun pointAt(pts: List<Offset>, f: Float): Offset {
                val lens = pts.zipWithNext { a, b -> (b - a).getDistance() }
                var d = lens.sum() * f
                for (i in lens.indices) {
                    if (d <= lens[i]) {
                        val a = pts[i]; val b = pts[i + 1]
                        return a + (b - a) * (d / lens[i])
                    }
                    d -= lens[i]
                }
                return pts.last()
            }

            fun link(node: Offset, active: Boolean, color: Color, towardCenter: Boolean, power: Double) {
                val pts = route(node)
                for (i in 0 until pts.size - 1) {
                    drawLine(trackColor, pts[i], pts[i + 1], stroke, StrokeCap.Round)
                }
                if (!active) return
                for (i in 0 until pts.size - 1) {
                    drawLine(color.copy(alpha = 0.35f), pts[i], pts[i + 1], stroke, StrokeCap.Round)
                }
                val speed = when { power > 2000 -> 2f; power > 700 -> 1.5f; else -> 1f }
                val dots = 3
                for (k in 0 until dots) {
                    var f = ((phase * speed) + k.toFloat() / dots) % 1f
                    if (!towardCenter) f = 1f - f
                    val p = pointAt(pts, f)
                    drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), p)
                    drawCircle(color, 3.6.dp.toPx(), p)
                }
            }

            link(Offset(leftX, topY), solarOn, SolarColor, towardCenter = true, power = solarWatts)
            link(Offset(rightX, topY), gridOn, GridColor, towardCenter = true, power = gridWatts)
            link(Offset(leftX, botY), chargeOn || dischargeOn, if (chargeOn) BatteryColor else DischargeColor,
                towardCenter = dischargeOn, power = if (chargeOn) batteryChargingWatts else batteryDischargingWatts)
            link(Offset(rightX, botY), houseOn, LoadColor, towardCenter = false, power = houseWatts)

            // inverter glow
            drawCircle(
                Brush.radialGradient(listOf(SolarColor.copy(alpha = pulse * 0.45f), Color.Transparent), center, 46.dp.toPx()),
                46.dp.toPx(), center
            )
        }

        // Inverter hub
        Box(
            Modifier.align(Alignment.Center).offset(y = (-6).dp).size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.5.dp, SolarColor.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Memory, "Inverter", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }

        FlowNode(Modifier.align(Alignment.TopStart), Icons.Filled.WbSunny, SolarColor, "Solar",
            watts(solarWatts), solarOn)
        FlowNode(Modifier.align(Alignment.TopEnd), Icons.Filled.ElectricalServices, GridColor, "Grid (KE)",
            watts(gridWatts), gridOn)
        BatteryNode(Modifier.align(Alignment.BottomStart), batteryPercent, chargeOn, dischargeOn,
            if (chargeOn) batteryChargingWatts else batteryDischargingWatts)
        FlowNode(Modifier.align(Alignment.BottomEnd), Icons.Filled.Home, LoadColor, "Ghar",
            watts(houseWatts), houseOn)
    }
}

@Composable
private fun NodeTile(color: Color, active: Boolean, size: Dp = TILE, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(18.dp))
            .background(color.copy(alpha = if (active) 0.20f else 0.08f))
            .border(1.5.dp, color.copy(alpha = if (active) 0.75f else 0.2f), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun FlowNode(modifier: Modifier, icon: ImageVector, color: Color, label: String, value: String, active: Boolean) {
    Column(
        modifier.padding(PAD).width(NODE_W).height(NODE_H),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NodeTile(color, active) {
            Icon(icon, label, tint = if (active) color else color.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BatteryNode(modifier: Modifier, percent: Int?, charging: Boolean, discharging: Boolean, watts: Double) {
    val active = charging || discharging
    val color = if (discharging) DischargeColor else BatteryColor
    Column(
        modifier.padding(PAD).width(NODE_W).height(NODE_H),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NodeTile(color, active) {
            // fill level behind the icon
            val level = ((percent ?: 0) / 100f).coerceIn(0f, 1f)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(level)
                    .background(color.copy(alpha = 0.22f))
            )
            Icon(
                if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                "Battery", tint = color, modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(if (percent != null) "$percent%" else "--", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            when {
                charging -> "Charging ${watts(watts)}"
                discharging -> "Using ${watts(watts)}"
                else -> "Battery idle"
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
        )
    }
}
