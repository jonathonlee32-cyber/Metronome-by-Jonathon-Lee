package com.example.musicpractice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.ui.theme.MusicPracticeTheme
import kotlinx.coroutines.delay

/** 节拍器还在响时，页面每秒重算一次，让"进行中"那一行和统计数字跟着走。 */
private const val LIVE_REFRESH_MILLIS = 1_000L

/**
 * "每日时间记录"页面。
 *
 * 结构和 [MetronomeScreen] 一样是无状态的：只负责把 [state] 画出来。
 * 顶部是可回退的标题栏，下面是统计卡片 + 按日期分组的记录卡片，整体是一个可滚动列表。
 *
 * @param isSessionRunning 节拍器是否正在播放，决定要不要每秒刷新（live 数据）。
 * @param onRefresh 请 ViewModel 重新算一遍 [state]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeRecordsScreen(
    state: PracticeRecordsUiState,
    isSessionRunning: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 进入页面先算一次；正在播放时每个 1 秒再算一次。停止播放后循环自然结束，
    // 不会留一个空转的定时器在后台。
    LaunchedEffect(isSessionRunning) {
        onRefresh()
        while (isSessionRunning) {
            delay(LIVE_REFRESH_MILLIS)
            onRefresh()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "练习记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回节拍器"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 统计区域始终在页面顶部，一条记录都没有时显示 0分钟 ——
            // 它同时也是"这个页面是干什么的"的说明。
            item(key = "statistics") { StatisticsCard(state) }

            if (state.hasRecords) {
                // 用日期当 key：每秒刷新时列表项不会整体重建，滚动位置也就不会跳。
                items(items = state.days, key = { it.dateKey }) { day ->
                    PracticeDayCard(day)
                }
            } else {
                item(key = "empty") {
                    EmptyRecords(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight(0.65f)
                    )
                }
            }
        }
    }
}

/** 顶部统计区域：今日 / 本周 / 累计。 */
@Composable
private fun StatisticsCard(state: PracticeRecordsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatisticItem(label = "今日", value = state.todayLabel, modifier = Modifier.weight(1f))
            StatisticDivider()
            StatisticItem(label = "本周", value = state.weekLabel, modifier = Modifier.weight(1f))
            StatisticDivider()
            StatisticItem(label = "累计", value = state.totalLabel, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatisticItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        Spacer(modifier = Modifier.height(6.dp))
        // 数值可能是"27小时18分钟"这种比较长的字符串，允许折成两行，居中显示。
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun StatisticDivider() {
    Box(
        modifier = Modifier
            .height(32.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
    )
}

/** 一天的记录卡片：日期标题 + 当日累计 + 时间/时长表格。 */
@Composable
private fun PracticeDayCard(day: PracticeDayUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = day.dateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = day.weekdayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (day.isToday) "今日练习" else "当日练习",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = day.totalLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(10.dp))

            // 表头做得很轻：只是给两列一个名分，不希望它抢走记录的注意力。
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "时间",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "时长",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            for (row in day.rows) {
                PracticeRow(row)
            }
        }
    }
}

@Composable
private fun PracticeRow(row: PracticeRowUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.timeRangeLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        // 正在进行的那一行用时长的颜色点一下，一眼能看出它还"活着"。
        Text(
            text = row.durationLabel,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = if (row.isRunning) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/** 一条记录都没有时的占位内容。 */
@Composable
private fun EmptyRecords(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.DateRange,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "还没有练习记录",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "回到节拍器按下“开始”，练习时间会自动记录在这里",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PracticeRecordsScreenPreview() {
    MusicPracticeTheme {
        PracticeRecordsScreen(
            state = PracticeRecordsUiState(
                todayLabel = "1小时02分钟",
                weekLabel = "4小时35分钟",
                totalLabel = "27小时18分钟",
                days = listOf(
                    PracticeDayUi(
                        dateKey = "2026-09-21",
                        dateLabel = "9月21日",
                        weekdayLabel = "星期一",
                        isToday = true,
                        totalLabel = "1小时02分钟",
                        rows = listOf(
                            PracticeRowUi("14:05–14:32", "27分钟", isRunning = false),
                            PracticeRowUi("15:10–15:45", "35分钟", isRunning = false)
                        )
                    ),
                    PracticeDayUi(
                        dateKey = "2026-09-20",
                        dateLabel = "9月20日",
                        weekdayLabel = "星期日",
                        isToday = false,
                        totalLabel = "45分钟",
                        rows = listOf(PracticeRowUi("19:20–20:05", "45分钟", isRunning = false))
                    )
                )
            ),
            isSessionRunning = false,
            onBack = {},
            onRefresh = {}
        )
    }
}
