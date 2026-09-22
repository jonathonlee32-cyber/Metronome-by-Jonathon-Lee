package com.example.musicpractice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.musicpractice.tuner.NoteMatch
import com.example.musicpractice.tuner.PitchHistory
import com.example.musicpractice.tuner.TunerReading
import com.example.musicpractice.tuner.TunerNotes

/** 栏目上下两段"偏离区"（±10 音分以外）的灰色。 */
private val HistoryGray = Color(0xFF3F464D)

/** 栏目中间那一段（-10 ~ +10 音分）的绿色：和中央读数区"已经调准了"是同一个绿。 */
private val HistoryGreen = TunedGreen

/** 三条参考线：中间的 0 音分亮一点，上下的 ±50 音分暗一点，"清晰但不抢眼"。 */
private val HistoryCenterLine = Color(0x80FFFFFF)
private val HistoryEdgeLine = Color(0x47FFFFFF)

/** 音名标签：白字 + 半透明黑色底，压在绿色／灰色上都看得清。 */
private val HistoryLabelChip = Color(0x52000000)

/** 三条参考线的线宽（细一点，不抢轨迹）。 */
private val HistoryGridStrokeWidth = 1.5.dp

/** 白色轨迹曲线的线宽：细、连续、平滑。 */
private val HistoryCurveStrokeWidth = 2.5.dp

/** 音名标签的字号、内边距，以及标签底边与轨迹线之间留的空。 */
private val HistoryLabelFontSize = 10.sp
private val HistoryLabelPadding = 3.dp
private val HistoryLabelGap = 5.dp

/** 栏目的圆角。 */
private val HistoryPanelShape = RoundedCornerShape(12.dp)

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * 调音器页下方的"音准历史轨迹"栏目。
 *
 * 画的东西从上到下依次是：
 * 1. 背景：中间 `-10 ~ +10` 音分一条绿色（音准较准确），其余上下两段灰色；
 * 2. 三条贯穿整个栏目宽度的水平参考线：顶部 +50 音分、正中央 0 音分、底部 -50 音分；
 * 3. **一条**白色连续曲线：最近 [PitchHistory.windowMs] 毫秒里检测到的音高偏差连成的平滑轨迹。
 *    采样点只作为数据存在（见 [PitchHistory]），界面上**不画任何圆点／节点**；
 * 4. 音符变化时产生的音名标签（在轨迹起始位置的正上方，跟着轨迹一起向左移动）。
 *
 * 滚动是**按时间**算的：横坐标 = 右边缘 - (现在 - 这个点的时间) × 每毫秒多少像素，
 * 其中"现在"由 [PitchHistory] 的时间轴给出，而时间轴只在 [active] 为 true 时由这里的帧循环推进。
 * 所以：
 * - 屏幕刷新率不同、帧率高低，都不会让滚动速度变快或变慢；
 * - [active] 为 false（NOISE）时帧循环被取消，白色曲线和音名标签**完全静止**，
 *   恢复检测后再从右边缘继续 —— 不需要额外的"暂停"状态。
 *
 * @param history 音准历史数据（由 TunerViewModel 持有，这里只读）。
 * @param active 当前是否在滚动：调音器检测到有效音符（不是 NOISE）时为 true。
 * @param onFrame 每画一帧回调一次，参数是距离上一帧的毫秒数；界面用它推进 [PitchHistory] 的时间轴。
 *   **只在 [active] 为 true 时才会被调用**。
 */
@Composable
internal fun PitchHistoryPanel(
    history: PitchHistory,
    active: Boolean,
    onFrame: (deltaMillis: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(
            color = Color.White,
            fontSize = HistoryLabelFontSize,
            fontWeight = FontWeight.SemiBold
        )
    }
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    // 轨迹曲线：复用同一个 Path，每帧 reset 后重画，绘制过程中不产生新对象。
    val curvePath = remember { Path() }

    // 每画一帧自增一次。它在绘制阶段被读取，所以只会让栏目重绘、不会让整页重组。
    var frameTick by remember { mutableLongStateOf(0L) }

    // 帧循环：只在"有效音符"期间存在。NOISE 时这个 LaunchedEffect 被取消，一切静止。
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var lastNanos = 0L
        // 纳秒换算成毫秒时剩下的零头，攒到下一帧，避免每帧丢掉不到 1 毫秒导致滚动偏慢。
        var carryNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val elapsedNanos = nanos - lastNanos + carryNanos
                    val deltaMillis = elapsedNanos / NANOS_PER_MILLI
                    carryNanos = elapsedNanos - deltaMillis * NANOS_PER_MILLI
                    if (deltaMillis > 0L) onFrame(deltaMillis)
                }
                lastNanos = nanos
                frameTick++
            }
        }
    }

    Canvas(
        modifier = modifier
            .clip(HistoryPanelShape)
            .border(width = 1.dp, color = borderColor, shape = HistoryPanelShape)
    ) {
        // 读一下帧信号：它每帧都变，绘制因此每帧重来一次。不读它就没有动画。
        frameTick

        val panelWidth = size.width
        val panelHeight = size.height
        val gridStroke = HistoryGridStrokeWidth.toPx()
        val curveStroke = HistoryCurveStrokeWidth.toPx()
        // 时间 → 横向像素：整个时间窗口正好铺满栏目宽度。
        val pixelsPerMilli = panelWidth / history.windowMs
        val now = history.nowMs

        // 1) 背景：先整体铺灰色，再把 ±10 音分那一条（中间 20% 高度）盖成绿色。
        //    绿色区域上下对称，正中间就是 0 音分那条线。
        drawRect(color = HistoryGray, topLeft = Offset.Zero, size = size)
        val greenTop = panelHeight * centsToVerticalFraction(TunerNotes.IN_TUNE_CENTS)
        val greenBottom = panelHeight * centsToVerticalFraction(-TunerNotes.IN_TUNE_CENTS)
        drawRect(
            color = HistoryGreen,
            topLeft = Offset(0f, greenTop),
            size = Size(panelWidth, greenBottom - greenTop)
        )

        // 2) 三条水平参考线，贯穿整个栏目宽度：+50 在顶部、0 在正中央、-50 在底部。
        val edgeInset = gridStroke / 2f
        drawLine(HistoryEdgeLine, Offset(0f, edgeInset), Offset(panelWidth, edgeInset), gridStroke)
        drawLine(
            HistoryCenterLine,
            Offset(0f, panelHeight / 2f),
            Offset(panelWidth, panelHeight / 2f),
            gridStroke
        )
        drawLine(
            HistoryEdgeLine,
            Offset(0f, panelHeight - edgeInset),
            Offset(panelWidth, panelHeight - edgeInset),
            gridStroke
        )

        // 3) 轨迹：只画一条连续的白色曲线。
        //    采样点只用来算曲线（这里有约 22 个/秒），界面上不画点、不画节点。
        //    平滑方式：每一段用"上一个采样点"作控制点、用"这两个采样点的中点"作终点，
        //    二次贝塞尔在相邻中点处切线相同，于是整条线连续且没有折角。
        val pointCount = history.pointCount
        curvePath.reset()
        var drawnPoints = 0
        var previousX = 0f
        var previousY = 0f
        for (index in 0 until pointCount) {
            val x = panelWidth - (now - history.pointTime(index)) * pixelsPerMilli
            if (x > panelWidth + curveStroke) break // 再往后只会更靠右
            // 上下各留半个线宽：贴着 ±50 的轨迹不会只画出一半。
            val y = verticalPositionOf(panelHeight, history.pointCents(index), curveStroke / 2f)
            if (drawnPoints == 0) {
                curvePath.moveTo(x, y)
            } else {
                curvePath.quadraticBezierTo(
                    x1 = previousX,
                    y1 = previousY,
                    x2 = (previousX + x) / 2f,
                    y2 = (previousY + y) / 2f
                )
            }
            previousX = x
            previousY = y
            drawnPoints++
        }
        if (drawnPoints > 1) {
            // 收尾：把曲线补到最后一个采样点上（也就是最右边、最新的那个）。
            curvePath.lineTo(previousX, previousY)
            drawPath(
                path = curvePath,
                color = Color.White,
                style = Stroke(
                    width = curveStroke,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // 4) 音名标签：只在换音时才有，画在轨迹起始位置的正上方，跟着轨迹一起向左移动。
        var lastLabelRight = Float.NEGATIVE_INFINITY
        val labelPadding = HistoryLabelPadding.toPx()
        for (index in 0 until history.markerCount) {
            val x = panelWidth - (now - history.markerTime(index)) * pixelsPerMilli
            if (x > panelWidth + curveStroke) break

            val layout = textMeasurer.measure(text = history.markerName(index), style = labelStyle)
            val textWidth = layout.size.width.toFloat()
            val textHeight = layout.size.height.toFloat()
            // 标签以对应轨迹位置为中心：刚换音时它有一小截在右边缘外，跟着轨迹往左走就会完整露出来，
            // 之后又跟着自己的位置一起滑出左边缘 —— 全程都贴着轨迹走，不会在边上停住。
            val left = x - textWidth / 2f
            if (left + textWidth < 0f) continue
            // 两个标签挨得太近时只留先出现的那个，避免叠在一起看不清。
            if (left < lastLabelRight + labelPadding) continue
            lastLabelRight = left + textWidth

            // 标签底边贴在"曲线在这里的位置"上方一点点；万一曲线已经贴着顶部，
            // 就把标签压在栏目顶端 —— 绝不越出栏目，也不盖住轨迹线。
            val pointY = verticalPositionOf(panelHeight, history.markerCents(index), curveStroke / 2f)
            val chipBottom = (pointY - HistoryLabelGap.toPx() - labelPadding)
                .coerceAtLeast(labelPadding)
            val chipTop = (chipBottom - textHeight - 2f * labelPadding).coerceAtLeast(0f)

            drawRoundRect(
                color = HistoryLabelChip,
                topLeft = Offset(left - labelPadding, chipTop),
                size = Size(textWidth + 2f * labelPadding, textHeight + 2f * labelPadding),
                cornerRadius = CornerRadius(labelPadding)
            )
            drawText(layout, topLeft = Offset(left, chipTop + labelPadding))
        }
    }
}

/**
 * 音分偏差 → 栏目内的垂直位置比例：0 = 顶部的 +50 音分，0.5 = 正中央的 0 音分，1 = 底部的 -50 音分。
 *
 * 超出 ±50 音分的检测结果会被夹到边界上，所以轨迹永远不会跑出栏目。
 */
internal fun centsToVerticalFraction(cents: Int): Float {
    val maxCents = TunerNotes.NOTE_RANGE_CENTS.toInt()
    val clamped = cents.coerceIn(-maxCents, maxCents)
    return (maxCents - clamped) / (2f * maxCents)
}

/** 音分 → 栏目内的纵向像素位置；[inset] 一般给半个线宽，保证贴着 ±50 的轨迹不会被边缘切掉一半。 */
private fun verticalPositionOf(panelHeight: Float, cents: Int, inset: Float): Float {
    val top = inset
    val bottom = (panelHeight - inset).coerceAtLeast(top)
    return (panelHeight * centsToVerticalFraction(cents)).coerceIn(top, bottom)
}

/**
 * 预览用的示例轨迹：模拟一段 C4 → D4 → E4 的检测结果（每点间隔约 46 毫秒，和真机一致），
 * 让 @Preview 里也能看到连续曲线和音名标签。
 */
internal fun previewPitchHistory(): PitchHistory {
    val history = PitchHistory()
    val segments = listOf(
        60 to intArrayOf(-6, -4, -2, 0, 1, 3, 4, 5, 4, 3, 2, 2),
        62 to intArrayOf(-17, -15, -12, -9, -8, -6, -5, -4, -4, -3),
        64 to intArrayOf(2, 3, 4, 5, 5, 4, 3, 2)
    )

    for ((midi, centsList) in segments) {
        for (cents in centsList) {
            history.advance(46)
            val frequency = TunerNotes.frequencyHz(midi, 440.0)
            history.onReading(
                TunerReading(
                    isReliable = true,
                    match = NoteMatch(
                        midi = midi,
                        name = TunerNotes.nameOf(midi),
                        targetFrequencyHz = frequency,
                        cents = cents.toDouble()
                    ),
                    frequencyHz = frequency
                )
            )
        }
    }
    return history
}
