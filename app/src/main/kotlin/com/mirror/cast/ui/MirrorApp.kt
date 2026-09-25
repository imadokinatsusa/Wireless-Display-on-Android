package com.mirror.cast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 两个角色就是两个页签：投屏 / 接收。 */
private enum class MirrorTab(val label: String, val icon: ImageVector) {
    Sender("投屏", Icons.Filled.ScreenShare),
    Receiver("接收", Icons.Filled.Tv),
}

/**
 * 应用外壳：**底部切换栏 + 内容区共存**。
 *
 * - 没有"首页"，也没有来回导航：两个角色就是两个页签，随时切换；
 * - `Scaffold` 替我们处理系统栏内边距（`innerPadding`）—— 全面屏与挖孔屏不会被挡住；
 * - 接收端一进全屏，切换栏自动隐藏（沉浸看画面），退出全屏再回来。
 */
@Composable
fun MirrorApp(lastCrash: String?) {
    var tab by remember { mutableStateOf(MirrorTab.Sender) }
    var receiverFullscreen by remember { mutableStateOf(false) }

    MirrorTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!receiverFullscreen) {
                    MirrorTabBar(current = tab, onSelect = { tab = it })
                }
            },
        ) { innerPadding ->
            Box(
                modifier = if (receiverFullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                },
            ) {
                when (tab) {
                    MirrorTab.Sender -> SenderContent(lastCrash = lastCrash)
                    MirrorTab.Receiver -> ReceiverContent(
                        onFullscreenChange = { receiverFullscreen = it },
                    )
                }
            }
        }
    }
}

/** iOS 风格的底部页签栏：细分割线 + 图标与短标签。 */
@Composable
private fun MirrorTabBar(
    current: MirrorTab,
    onSelect: (MirrorTab) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MirrorTab.entries.forEach { entry ->
                val selected = entry == current
                Column(
                    modifier = Modifier
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 26.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = entry.icon,
                        contentDescription = entry.label,
                        modifier = Modifier.size(24.dp),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
