package io.github.fuyunonatsuki.kanbrowser

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun MainScreen(
    web: WebController,
    update: UpdateState,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (UpdateInfo) -> Unit,
    onExactAlarm: () -> Unit,
) {
    var showPanel by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showOffset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val game = web.game

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6FA8DC))) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { web.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize(),
            )

            if (web.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }

            // 画面左端（ゲーム画面の外の黒帯）に置くボタン
            Column(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                Box {
                    EdgeButton(Icons.Filled.Menu, Color.White.copy(alpha = 0.35f)) { showMenu = true }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("再読み込み") }, onClick = { showMenu = false; web.reload() })
                        DropdownMenuItem(text = { Text("ゲームページへ") }, onClick = { showMenu = false; web.goHome() })
                        DropdownMenuItem(
                            text = { Text("画面にフィット") },
                            trailingIcon = { Switch(checked = web.fitMode, onCheckedChange = null) },
                            onClick = { web.setFit(!web.fitMode) },
                        )
                        DropdownMenuItem(text = { Text("表示位置の微調整") }, onClick = { showMenu = false; showOffset = true })
                        DropdownMenuItem(text = { Text("スクリーンショット") }, onClick = { showMenu = false; web.snapshot() })
                        DropdownMenuItem(text = { Text("通知を正確な時刻にする") }, onClick = { showMenu = false; onExactAlarm() })
                        DropdownMenuItem(
                            text = { Text("アップデートを確認（v${BuildConfig.VERSION_NAME}）") },
                            onClick = { showMenu = false; onCheckUpdate() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("ログアウト（データ消去）", color = Color(0xFFFF6B6B)) },
                            onClick = { showMenu = false; confirmClear = true },
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
                EdgeButton(
                    if (showPanel) Icons.Filled.Close else Icons.Filled.List,
                    if (game.hasHeavyInSortie) Color.Red else Color.White.copy(alpha = 0.35f),
                ) { showPanel = !showPanel }
            }

            AnimatedVisibility(
                visible = showPanel,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                StatusPanel(game, onClose = { showPanel = false },
                    modifier = Modifier.width(minOf(440.dp, maxWidth * 0.5f)).fillMaxHeight())
            }

            // 戦闘後に大破艦がいたら上部に警告（タップで閉じる）
            if (game.heavyAlert.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        .background(Color(0xE6D32F2F), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { game.heavyAlert = emptyList() }) {
                        Text("大破：" + game.heavyAlert.joinToString("、"), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (showOffset) {
            AlertDialog(
                onDismissRequest = { showOffset = false },
                confirmButton = { TextButton(onClick = { showOffset = false }) { Text("完了") } },
                dismissButton = { TextButton(onClick = { web.setOffset(0) }) { Text("リセット") } },
                title = { Text("表示位置の微調整") },
                text = {
                    Column {
                        Text("縦オフセット：${web.yOffset} px")
                        Slider(
                            value = web.yOffset.toFloat(),
                            onValueChange = { web.setOffset(it.toInt()) },
                            valueRange = -60f..60f, steps = 119,
                        )
                        Text("ゲーム画面の上端に帯や切れが出る場合に調整してください。プラスで上にずらします。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; web.clearAllData() }) {
                        Text("消去する", color = Color(0xFFFF6B6B))
                    }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("キャンセル") } },
                title = { Text("Cookieとキャッシュをすべて消去しますか？") },
            )
        }

        update.info?.let { info ->
            AlertDialog(
                onDismissRequest = { update.info = null },
                confirmButton = { TextButton(onClick = { onInstallUpdate(info) }) { Text("更新する") } },
                dismissButton = { TextButton(onClick = { update.info = null }) { Text("あとで") } },
                title = { Text("新しいバージョンがあります") },
                text = { Text("v${BuildConfig.VERSION_NAME} → v${info.versionName}\n\n${info.notes.take(300)}") },
            )
        }

        if (update.upToDate) {
            AlertDialog(
                onDismissRequest = { update.upToDate = false },
                confirmButton = { TextButton(onClick = { update.upToDate = false }) { Text("OK") } },
                text = { Text("最新バージョン（v${BuildConfig.VERSION_NAME}）です") },
            )
        }
    }
}

@Composable
private fun EdgeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Box(Modifier.size(32.dp).background(tint.copy(alpha = tint.alpha * 0.5f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White.copy(alpha = if (tint == Color.Red) 1f else 0.7f))
        }
    }
}
