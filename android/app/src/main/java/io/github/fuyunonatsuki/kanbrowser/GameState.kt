package io.github.fuyunonatsuki.kanbrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

// MARK: - モデル

enum class Damage(val label: String, val color: Color) {
    NONE("", Color(0xFF4CAF50)),
    LIGHT("小破", Color(0xFFFFEB3B)),
    MEDIUM("中破", Color(0xFFFF9800)),
    HEAVY("大破", Color(0xFFF44336)),
    SUNK("轟沈", Color(0xFFF44336));

    companion object {
        fun of(now: Int, max: Int): Damage {
            if (max <= 0) return NONE
            if (now <= 0) return SUNK
            val r = now.toDouble() / max
            return when {
                r <= 0.25 -> HEAVY
                r <= 0.5 -> MEDIUM
                r <= 0.75 -> LIGHT
                else -> NONE
            }
        }
    }
}

data class Ship(
    val id: Int,
    val masterId: Int = 0,
    val lv: Int = 0,
    val nowhp: Int = 0,
    val maxhp: Int = 0,
    val cond: Int = 49,
    val fuel: Int = 0,
    val bull: Int = 0,
) {
    val damage: Damage get() = Damage.of(nowhp, maxhp)
}

data class Deck(
    val id: Int,
    val name: String,
    val shipIds: List<Int>,
    val missionState: Int,     // 0:なし 1:遠征中 2:帰還 3:中止
    val missionId: Int,
    val missionEnd: Long,      // epoch ms（0 = なし）
)

data class DockInfo(
    val id: Int,
    val state: Int,            // 入渠 1:使用中 / 建造 2:建造中 3:完成
    val shipId: Int,           // 入渠:艦ID / 建造:艦船マスターID
    val complete: Long,
)

data class Quest(
    val id: Int,
    val title: String,
    val category: Int,
    val state: Int,            // 2:遂行中 3:達成
    val progress: Int,         // 0 / 1:50% / 2:80%
)

data class ShipMaster(val name: String, val fuel: Int, val bull: Int)

// MARK: - 状態

/** ゲームのAPI応答（読み取り専用）から艦隊・遠征・ドック・任務の状態を組み立てる */
class GameState(context: Context) {
    var ships by mutableStateOf<Map<Int, Ship>>(emptyMap()); private set
    var decks by mutableStateOf<List<Deck>>(emptyList()); private set
    var ndocks by mutableStateOf<List<DockInfo>>(emptyList()); private set
    var kdocks by mutableStateOf<List<DockInfo>>(emptyList()); private set
    var quests by mutableStateOf<Map<Int, Quest>>(emptyMap()); private set
    var sortieDecks by mutableStateOf<Set<Int>>(emptySet()); private set
    var lastUpdate by mutableStateOf(0L); private set
    var heavyAlert by mutableStateOf<List<String>>(emptyList())

    private var shipMaster: Map<Int, ShipMaster> = emptyMap()
    private var missionMaster: Map<Int, String> = emptyMap()
    private val cacheFile = File(context.filesDir, "master.json")
    var onTimersChanged: (() -> Unit)? = null

    init {
        runCatching { if (cacheFile.exists()) parseMaster(JSONObject(cacheFile.readText()), save = false) }
    }

    // MARK: 表示用ヘルパー

    fun name(ship: Ship) = shipMaster[ship.masterId]?.name ?: "艦ID ${ship.masterId}"
    fun shipName(masterId: Int) = shipMaster[masterId]?.name ?: "艦ID $masterId"
    fun missionName(id: Int) = missionMaster[id] ?: "遠征 $id"
    fun fuelMax(s: Ship) = shipMaster[s.masterId]?.fuel ?: 0
    fun bullMax(s: Ship) = shipMaster[s.masterId]?.bull ?: 0
    fun members(deck: Deck) = deck.shipIds.mapNotNull { ships[it] }

    val hasHeavyInSortie: Boolean
        get() = decks.filter { it.id in sortieDecks }.flatMap { members(it) }.any { it.damage == Damage.HEAVY }

    // MARK: 受信

    fun handle(path: String, body: String, text: String) {
        val raw = text.removePrefix("svdata=")
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.optInt("api_result") != 1) return
        val d = json.opt("api_data")
        val req = parseForm(body)

        when (path) {
            "api_start2/getData" -> parseMaster(d as? JSONObject ?: return, save = true)
            "api_port/port" -> {
                val p = d as? JSONObject ?: return
                ships = emptyMap()
                mergeShips(p.optJSONArray("api_ship"))
                decks = p.optJSONArray("api_deck_port").objects().map(::parseDeck)
                ndocks = p.optJSONArray("api_ndock").objects().map(::parseDock)
                sortieDecks = emptySet()
                heavyAlert = emptyList()
                onTimersChanged?.invoke()
            }
            "api_get_member/ship_deck", "api_get_member/ship3" -> {
                val p = d as? JSONObject ?: return
                mergeShips(p.optJSONArray("api_ship_data"))
                mergeDecks(p.optJSONArray("api_deck_data"))
            }
            "api_get_member/ship2" -> {
                mergeShips(d as? JSONArray)
                mergeDecks(json.optJSONArray("api_data_deck"))
            }
            "api_get_member/deck" -> mergeDecks(d as? JSONArray)
            "api_get_member/ndock" -> {
                ndocks = (d as? JSONArray).objects().map(::parseDock)
                onTimersChanged?.invoke()
            }
            "api_get_member/kdock" -> {
                kdocks = (d as? JSONArray).objects().map(::parseKdock)
                onTimersChanged?.invoke()
            }
            "api_req_kousyou/getship" -> {
                val p = d as? JSONObject ?: return
                kdocks = p.optJSONArray("api_kdock").objects().map(::parseKdock)
                p.optJSONObject("api_ship")?.let { mergeShips(JSONArray().put(it)) }
                onTimersChanged?.invoke()
            }
            "api_req_hokyu/charge" -> {
                val m = ships.toMutableMap()
                for (s in (d as? JSONObject)?.optJSONArray("api_ship").objects()) {
                    val id = s.optInt("api_id")
                    m[id]?.let { m[id] = it.copy(fuel = s.optInt("api_fuel"), bull = s.optInt("api_bull")) }
                }
                ships = m
            }
            "api_req_nyukyo/start" -> {
                if (req["api_highspeed"] == "1") {
                    val id = req["api_ship_id"]?.toIntOrNull()
                    id?.let { ships[it] }?.let { ships = ships + (it.id to it.copy(nowhp = it.maxhp)) }
                }
            }
            "api_req_nyukyo/speedchange" -> {
                val dockId = req["api_ndock_id"]?.toIntOrNull()
                val dock = ndocks.firstOrNull { it.id == dockId }
                if (dock != null) {
                    ships[dock.shipId]?.let { ships = ships + (it.id to it.copy(nowhp = it.maxhp)) }
                    ndocks = ndocks.map { if (it.id == dockId) it.copy(state = 0, complete = 0) else it }
                    onTimersChanged?.invoke()
                }
            }
            "api_get_member/questlist" -> parseQuests(d as? JSONObject ?: return, req["api_tab_id"]?.toIntOrNull() ?: 0)
            "api_req_quest/clearitemget", "api_req_quest/stop" -> {
                req["api_quest_id"]?.toIntOrNull()?.let { quests = quests - it }
            }
            "api_req_map/start" -> {
                val deck = req["api_deck_id"]?.toIntOrNull()
                if (deck != null) {
                    var s = setOf(deck)
                    if (deck == 1 && decks.size > 1 && (req["api_combined"]?.toIntOrNull() ?: 0) > 0) s = s + 2
                    sortieDecks = s
                }
            }
            else -> {
                val isBattle = (path.startsWith("api_req_sortie/") || path.startsWith("api_req_battle_midnight/")
                        || path.startsWith("api_req_combined_battle/"))
                        && !path.contains("battleresult") && !path.contains("goback_port")
                if (!isBattle) return
                applyBattle(d as? JSONObject ?: return)
            }
        }
        lastUpdate = System.currentTimeMillis()
    }

    // MARK: パース

    private fun parseMaster(d: JSONObject, save: Boolean) {
        val sm = HashMap<Int, ShipMaster>()
        for (s in d.optJSONArray("api_mst_ship").objects()) {
            val fuel = s.optInt("api_fuel_max"); val bull = s.optInt("api_bull_max")
            sm[s.optInt("api_id")] = ShipMaster(s.optString("api_name", "?"), fuel, bull)
        }
        val mm = HashMap<Int, String>()
        for (s in d.optJSONArray("api_mst_mission").objects()) {
            val id = s.optInt("api_id")
            mm[id] = "${s.optString("api_disp_no", id.toString())} ${s.optString("api_name")}"
        }
        shipMaster = sm
        missionMaster = mm
        if (save) {
            // 必要な項目だけ保存
            val out = JSONObject()
            out.put("api_mst_ship", JSONArray().apply {
                sm.forEach { (id, m) ->
                    put(JSONObject().put("api_id", id).put("api_name", m.name)
                        .put("api_fuel_max", m.fuel).put("api_bull_max", m.bull))
                }
            })
            out.put("api_mst_mission", JSONArray().apply {
                for (s in d.optJSONArray("api_mst_mission").objects()) {
                    put(JSONObject().put("api_id", s.optInt("api_id"))
                        .put("api_disp_no", s.optString("api_disp_no")).put("api_name", s.optString("api_name")))
                }
            })
            runCatching { cacheFile.writeText(out.toString()) }
        }
    }

    private fun mergeShips(list: JSONArray?) {
        val m = ships.toMutableMap()
        for (s in list.objects()) {
            val id = s.optInt("api_id"); if (id <= 0) continue
            m[id] = Ship(
                id = id,
                masterId = s.optInt("api_ship_id"),
                lv = s.optInt("api_lv"),
                nowhp = s.optInt("api_nowhp"),
                maxhp = s.optInt("api_maxhp"),
                cond = s.optInt("api_cond", 49),
                fuel = s.optInt("api_fuel"),
                bull = s.optInt("api_bull"),
            )
        }
        ships = m
    }

    private fun mergeDecks(list: JSONArray?) {
        val items = list.objects().map(::parseDeck)
        if (items.isEmpty()) return
        val m = decks.associateBy { it.id }.toMutableMap()
        items.forEach { m[it.id] = it }
        decks = m.values.sortedBy { it.id }
        onTimersChanged?.invoke()
    }

    private fun parseDeck(d: JSONObject): Deck {
        val m = d.optJSONArray("api_mission").longs()
        return Deck(
            id = d.optInt("api_id"),
            name = d.optString("api_name"),
            shipIds = d.optJSONArray("api_ship").longs().map { it.toInt() }.filter { it > 0 },
            missionState = m.getOrElse(0) { 0L }.toInt(),
            missionId = m.getOrElse(1) { 0L }.toInt(),
            missionEnd = m.getOrElse(2) { 0L },
        )
    }

    private fun parseDock(d: JSONObject) =
        DockInfo(d.optInt("api_id"), d.optInt("api_state"), d.optInt("api_ship_id"), d.optLong("api_complete_time"))

    private fun parseKdock(d: JSONObject) =
        DockInfo(d.optInt("api_id"), d.optInt("api_state"), d.optInt("api_created_ship_id"), d.optLong("api_complete_time"))

    private fun parseQuests(d: JSONObject, tab: Int) {
        val seen = HashMap<Int, Quest>()
        val removed = HashSet<Int>()
        val list = d.optJSONArray("api_list")
        if (list != null) for (i in 0 until list.length()) {
            val q = list.optJSONObject(i) ?: continue   // 空き枠は -1 が入る
            val no = q.optInt("api_no"); val state = q.optInt("api_state")
            if (state == 2 || state == 3) {
                seen[no] = Quest(no, q.optString("api_title", "任務 $no"), q.optInt("api_category"), state,
                    q.optInt("api_progress_flag"))
            } else removed += no
        }
        // 「遂行中」タブで1ページに収まっていれば、それを正として置き換え
        quests = if (tab == 9 && d.optInt("api_page_count") <= 1) seen
        else (quests - removed) + seen
    }

    // MARK: 戦闘（被ダメージを計算して戦闘後のHPを予測）

    private fun applyBattle(d: JSONObject) {
        val deckId = if (d.has("api_deck_id")) d.optInt("api_deck_id") else d.optInt("api_dock_id")
        val main = decks.firstOrNull { it.id == deckId } ?: return
        val hpMain = d.optJSONArray("api_f_nowhps").longs().map { it.toInt() }.toMutableList()
        val hpEscort = d.optJSONArray("api_f_nowhps_combined").longs().map { it.toInt() }.toMutableList()
        val combined = hpEscort.isNotEmpty()
        if (hpMain.isEmpty()) return

        fun hit(index: Int, dmg: Double) {
            val v = dmg.toInt()   // 小数点以下（かばい判定の .1）を切り捨て
            if (v <= 0) return
            if (combined && index >= 6) {
                val i = index - 6
                if (i < hpEscort.size) hpEscort[i] = maxOf(0, hpEscort[i] - v)
            } else if (index < hpMain.size) {
                hpMain[index] = maxOf(0, hpMain[index] - v)
            }
        }
        fun fdam(list: JSONArray?, escort: Boolean = false) {
            if (list == null) return
            for (i in 0 until list.length()) hit(if (escort) i + 6 else i, list.optDouble(i, 0.0))
        }

        // 航空戦
        for (key in listOf("api_injection_kouku", "api_kouku", "api_kouku2")) {
            val k = d.optJSONObject(key) ?: continue
            fdam(k.optJSONObject("api_stage3")?.optJSONArray("api_fdam"))
            if (combined) fdam(k.optJSONObject("api_stage3_combined")?.optJSONArray("api_fdam"), escort = true)
        }
        // 雷撃戦
        for (key in listOf("api_opening_atack", "api_raigeki")) {
            fdam(d.optJSONObject(key)?.optJSONArray("api_fdam"))
        }
        // 砲撃戦（敵の攻撃のみ）
        for (key in listOf("api_opening_taisen", "api_hougeki1", "api_hougeki2", "api_hougeki3",
            "api_hougeki", "api_n_hougeki1", "api_n_hougeki2")) {
            val h = d.optJSONObject(key) ?: continue
            val flags = h.optJSONArray("api_at_eflag") ?: continue
            val targets = h.optJSONArray("api_df_list") ?: continue
            val damages = h.optJSONArray("api_damage") ?: continue
            val n = minOf(flags.length(), targets.length(), damages.length())
            for (i in 0 until n) {
                if (flags.optInt(i) != 1) continue
                val ts = targets.optJSONArray(i) ?: continue
                val ds = damages.optJSONArray(i) ?: continue
                for (j in 0 until minOf(ts.length(), ds.length())) hit(ts.optInt(j), ds.optDouble(j, 0.0))
            }
        }

        val alert = mutableListOf<String>()
        val m = ships.toMutableMap()
        fun update(deck: Deck, hps: List<Int>) {
            deck.shipIds.forEachIndexed { i, sid ->
                if (i >= hps.size) return@forEachIndexed
                val s = m[sid] ?: return@forEachIndexed
                val ns = s.copy(nowhp = hps[i]); m[sid] = ns
                if (ns.damage == Damage.HEAVY) alert += name(ns)
            }
        }
        update(main, hpMain)
        var sortie = sortieDecks + main.id
        if (combined) decks.firstOrNull { it.id == 2 }?.let { update(it, hpEscort); sortie = sortie + 2 }
        ships = m
        sortieDecks = sortie
        heavyAlert = alert
    }

    companion object {
        fun parseForm(s: String): Map<String, String> = s.split("&").filter { it.isNotEmpty() }.associate {
            val kv = it.split("=", limit = 2)
            val dec = { x: String -> runCatching { URLDecoder.decode(x, "UTF-8") }.getOrDefault(x) }
            dec(kv[0]) to dec(kv.getOrElse(1) { "" })
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONArray?.longs(): List<Long> {
    if (this == null) return emptyList()
    return (0 until length()).map { optLong(it) }
}
