@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.fuyunonatsuki.kanbrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Sub = Color(0xFF9AA5B4)
private val Green = Color(0xFF6FCF97)

/** タップで開く情報パネル（艦隊 / 遠征・ドック / 任務） */
@Composable
fun StatusPanel(game: GameState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    Column(modifier.background(Color(0xF0171E28)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryTabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f), containerColor = Color.Transparent) {
                listOf("艦隊", "遠征・ドック", "任務").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "閉じる", tint = Color.White) }
        }
        Spacer(Modifier.height(6.dp))

        if (game.lastUpdate == 0L) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("ゲームにログインして母港を開くと表示されます", color = Sub, fontSize = 13.sp)
            }
            return@Column
        }

        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (tab) {
                0 -> FleetView(game)
                1 -> TimersView(game, now)
                else -> QuestsView(game)
            }
        }
    }
}

// MARK: 艦隊

@Composable
private fun FleetView(game: GameState) {
    var deckId by remember { mutableIntStateOf(1) }
    game.sortie?.let { SortieBanner(game, it) }
    if (game.decks.isNotEmpty()) {
        SecondaryTabRow(selectedTabIndex = game.decks.indexOfFirst { it.id == deckId }.coerceAtLeast(0),
            containerColor = Color.Transparent) {
            game.decks.forEach { d ->
                Tab(selected = d.id == deckId, onClick = { deckId = d.id }, text = { Text("第${d.id}") })
            }
        }
    }
    val deck = game.decks.firstOrNull { it.id == deckId } ?: return
    val members = game.members(deck)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(deck.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
        if (deck.id in game.sortieDecks) Tag("出撃中", Color(0xFF2F6FED))
        if (deck.missionState == 1) Tag("遠征中", Color(0xFF1E9E95))
        Spacer(Modifier.weight(1f))
        Text(if (game.showEquipment) "装備▲" else "装備▼", color = Color(0xFF6FA8DC), fontSize = 12.sp,
            modifier = Modifier.clickable { game.showEquipment = !game.showEquipment }.padding(horizontal = 8.dp))
        members.minOfOrNull { it.cond }?.let {
            Text("最低cond $it", color = condColor(it), fontSize = 12.sp)
        }
    }
    members.forEach { ShipRow(game, it) }
}

/** 出撃中のマップ・現在のマス・経路 */
@Composable
private fun SortieBanner(game: GameState, sortie: Sortie) {
    val name = { c: Int -> game.cellName(sortie.area, sortie.map, c) }
    val event = GameState.eventLabel(sortie.eventId, sortie.eventKind)
    Column(
        Modifier.fillMaxWidth().background(Color(0x332F6FED), RoundedCornerShape(8.dp)).padding(8.dp),
    ) {
        Text("⚑ " + game.mapName(sortie.area, sortie.map), color = Color.White, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("現在 ", color = Sub, fontSize = 12.sp)
            Text(name(sortie.cell), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (event.isNotEmpty()) {
                Text(event, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                        .background(if (sortie.eventId == 5) Color(0x99F44336) else Color(0x662F6FED), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 1.dp))
            }
            Spacer(Modifier.weight(1f))
            if (sortie.bossCell > 0) Text("ボス ${name(sortie.bossCell)}", color = Sub, fontSize = 12.sp)
        }
        if (sortie.route.size > 1) {
            Text("経路 " + sortie.route.joinToString(" → ") { name(it) }, color = Sub, fontSize = 11.sp, maxLines = 2)
        }
    }
}

@Composable
private fun ShipRow(game: GameState, ship: Ship) {
    val dmg = ship.damage
    val fMax = game.fuelMax(ship); val bMax = game.bullMax(ship)
    Column(
        Modifier.fillMaxWidth()
            .background(if (dmg == Damage.HEAVY) Color(0x40F44336) else Color(0x10FFFFFF), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(game.name(ship), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Text("Lv${ship.lv}", color = Sub, fontSize = 12.sp)
            if (dmg.label.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(dmg.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(dmg.color, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(if (ship.cond >= 50) "✦ ${ship.cond}" else "${ship.cond}", color = condColor(ship.cond),
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { if (ship.maxhp > 0) ship.nowhp.toFloat() / ship.maxhp else 0f },
                color = dmg.color, trackColor = Color(0x33FFFFFF), modifier = Modifier.weight(1f),
            )
            Text("${ship.nowhp}/${ship.maxhp}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(64.dp).padding(start = 6.dp))
            if (fMax > 0 && (ship.fuel < fMax || ship.bull < bMax)) Text("補給", color = Color(0xFFFF9800), fontSize = 11.sp)
        }
        if (game.showEquipment) {
            val eq = game.equipment(ship)
            if (eq.isNotEmpty()) Column(Modifier.padding(top = 3.dp)) {
                eq.forEach { line ->
                    Text("・$line", color = Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// MARK: 遠征・ドック

@Composable
private fun TimersView(game: GameState, now: Long) {
    Section("遠征") {
        game.decks.filter { it.id > 1 }.forEach { d ->
            TimerRow("第${d.id}艦隊", if (d.missionState == 0) "待機中" else game.missionName(d.missionId),
                if (d.missionState == 0) 0 else d.missionEnd, now)
        }
    }
    Section("入渠") {
        game.ndocks.filter { it.state >= 0 }.forEach { d ->
            TimerRow("第${d.id}ドック",
                if (d.state == 1) game.ships[d.shipId]?.let(game::name) ?: "" else "空き",
                if (d.state == 1) d.complete else 0, now)
        }
    }
    if (game.kdocks.isNotEmpty()) Section("建造") {
        game.kdocks.filter { it.state >= 0 }.forEach { d ->
            TimerRow("第${d.id}ドック", if (d.state == 0) "空き" else game.shipName(d.shipId),
                if (d.state == 2) d.complete else 0, now, doneText = if (d.state == 3) "完成" else null)
        }
    }
}

@Composable
private fun TimerRow(title: String, detail: String, end: Long, now: Long, doneText: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Sub, fontSize = 11.sp)
            Text(detail, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (end > 0) {
            val left = end - now
            Text(if (left > 0) format(left) else "完了",
                color = when { left <= 0 -> Green; left < 60_000 -> Color(0xFFFFEB3B); else -> Color.White },
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        } else if (doneText != null) {
            Text(doneText, color = Green, fontWeight = FontWeight.Bold)
        }
    }
}

// MARK: 任務

@Composable
private fun QuestsView(game: GameState) {
    val list = game.quests.values.sortedWith(compareBy({ it.category }, { it.id }))
    if (list.isEmpty()) Text("ゲーム内で任務画面を開くと、受注中の任務が表示されます", color = Sub, fontSize = 13.sp)
    list.forEach { q ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(28.dp).background(categoryColor(q.category), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Text(q.title, color = Color.White, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
            Text(when { q.state == 3 -> "達成"; q.progress == 2 -> "80%"; q.progress == 1 -> "50%"; else -> "" },
                color = if (q.state == 3) Green else Sub, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
    Text("任務画面で表示したページの分だけ反映されます", color = Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

// MARK: 部品

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(title, color = Sub, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        content()
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 6.dp).background(color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp))
}

private fun categoryColor(c: Int) = when (c) {
    1 -> Color(0xFF4CAF50)          // 編成
    2, 8, 9 -> Color(0xFFF44336)    // 出撃
    3 -> Color(0xFF80CBC4)          // 演習
    4 -> Color(0xFF4DD0E1)          // 遠征
    5 -> Color(0xFFFFEB3B)          // 補給・入渠
    6 -> Color(0xFF8D6E63)          // 工廠
    7 -> Color(0xFFAB47BC)          // 改装
    else -> Color.Gray
}

fun condColor(cond: Int) = when {
    cond >= 50 -> Color(0xFFFFEB3B)
    cond >= 30 -> Color.White
    cond >= 20 -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}

private fun format(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
