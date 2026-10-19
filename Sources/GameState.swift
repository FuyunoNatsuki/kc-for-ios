import Foundation
import SwiftUI

// MARK: - モデル

enum Damage {
    case none, light, medium, heavy, sunk

    init(now: Int, max: Int) {
        guard max > 0 else { self = .none; return }
        if now <= 0 { self = .sunk; return }
        let r = Double(now) / Double(max)
        self = r <= 0.25 ? .heavy : r <= 0.5 ? .medium : r <= 0.75 ? .light : .none
    }

    var label: String {
        switch self {
        case .none: return ""
        case .light: return "小破"
        case .medium: return "中破"
        case .heavy: return "大破"
        case .sunk: return "轟沈"
        }
    }

    var color: Color {
        switch self {
        case .none: return .green
        case .light: return .yellow
        case .medium: return .orange
        case .heavy, .sunk: return .red
        }
    }
}

struct Ship: Identifiable {
    let id: Int
    var masterId = 0
    var lv = 0
    var nowhp = 0
    var maxhp = 0
    var cond = 49
    var fuel = 0
    var bull = 0

    var damage: Damage { Damage(now: nowhp, max: maxhp) }
}

struct Deck: Identifiable {
    let id: Int
    var name: String
    var shipIds: [Int]
    var missionState: Int      // 0:なし 1:遠征中 2:帰還 3:中止
    var missionId: Int
    var missionEnd: Date?
}

struct DockInfo: Identifiable {
    let id: Int
    var state: Int             // 入渠 1:使用中 / 建造 2:建造中 3:完成
    var shipId: Int            // 入渠:艦ID / 建造:艦船マスターID
    var complete: Date?
}

struct Quest: Identifiable {
    let id: Int
    var title: String
    var category: Int
    var state: Int             // 2:遂行中 3:達成
    var progress: Int          // 0 / 1:50% / 2:80%
}

struct ShipMaster: Codable { var name: String; var fuel: Int; var bull: Int }
struct MasterCache: Codable {
    var ships: [Int: ShipMaster] = [:]
    var missions: [Int: String] = [:]
}

// MARK: - 状態

/// ゲームのAPI応答（読み取り専用）から艦隊・遠征・ドック・任務の状態を組み立てる
@MainActor
final class GameState: ObservableObject {
    @Published private(set) var ships: [Int: Ship] = [:]
    @Published private(set) var decks: [Deck] = []
    @Published private(set) var ndocks: [DockInfo] = []
    @Published private(set) var kdocks: [DockInfo] = []
    @Published private(set) var quests: [Int: Quest] = [:]
    @Published private(set) var sortieDecks: Set<Int> = []
    @Published private(set) var lastUpdate: Date?
    @Published var heavyAlert: [String] = []

    private(set) var master = MasterCache()
    var onTimersChanged: (() -> Void)?

    private static var cacheURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("master.json")
    }

    init() {
        if let data = try? Data(contentsOf: Self.cacheURL),
           let m = try? JSONDecoder().decode(MasterCache.self, from: data) {
            master = m
        }
    }

    // MARK: 表示用ヘルパー

    func name(of ship: Ship) -> String { master.ships[ship.masterId]?.name ?? "艦ID \(ship.masterId)" }
    func shipName(masterId: Int) -> String { master.ships[masterId]?.name ?? "艦ID \(masterId)" }
    func missionName(_ id: Int) -> String { master.missions[id] ?? "遠征 \(id)" }
    func fuelMax(_ s: Ship) -> Int { master.ships[s.masterId]?.fuel ?? 0 }
    func bullMax(_ s: Ship) -> Int { master.ships[s.masterId]?.bull ?? 0 }
    func members(of deck: Deck) -> [Ship] { deck.shipIds.compactMap { ships[$0] } }

    var hasHeavyInSortie: Bool {
        decks.filter { sortieDecks.contains($0.id) }
            .flatMap { members(of: $0) }
            .contains { $0.damage == .heavy }
    }

    // MARK: 受信

    func handle(path: String, body: String, text: String) {
        var raw = text
        if raw.hasPrefix("svdata=") { raw.removeFirst(7) }
        guard let data = raw.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              int(json["api_result"]) == 1 else { return }
        let d = json["api_data"]
        let req = Self.parseForm(body)

        switch path {
        case "api_start2/getData":
            parseMaster(dict(d))
        case "api_port/port":
            let p = dict(d)
            ships = [:]
            mergeShips(arr(p["api_ship"]))
            decks = arr(p["api_deck_port"]).map(parseDeck)
            ndocks = arr(p["api_ndock"]).map(parseDock)
            sortieDecks = []
            heavyAlert = []
            onTimersChanged?()
        case "api_get_member/ship_deck", "api_get_member/ship3":
            let p = dict(d)
            mergeShips(arr(p["api_ship_data"]))
            mergeDecks(arr(p["api_deck_data"]))
        case "api_get_member/ship2":
            mergeShips(arr(d))
            mergeDecks(arr(json["api_data_deck"]))
        case "api_get_member/deck":
            mergeDecks(arr(d))
        case "api_get_member/ndock":
            ndocks = arr(d).map(parseDock)
            onTimersChanged?()
        case "api_get_member/kdock":
            kdocks = arr(d).map(parseKdock)
            onTimersChanged?()
        case "api_req_kousyou/getship":
            let p = dict(d)
            kdocks = arr(p["api_kdock"]).map(parseKdock)
            if let s = p["api_ship"] { mergeShips([s]) }
            onTimersChanged?()
        case "api_req_hokyu/charge":
            for item in arr(dict(d)["api_ship"]) {
                let s = dict(item); let id = int(s["api_id"])
                ships[id]?.fuel = int(s["api_fuel"])
                ships[id]?.bull = int(s["api_bull"])
            }
        case "api_req_nyukyo/start":
            if req["api_highspeed"] == "1", let id = Int(req["api_ship_id"] ?? ""), let s = ships[id] {
                ships[id]?.nowhp = s.maxhp
            }
        case "api_req_nyukyo/speedchange":
            if let dockId = Int(req["api_ndock_id"] ?? ""), let i = ndocks.firstIndex(where: { $0.id == dockId }) {
                let sid = ndocks[i].shipId
                if let s = ships[sid] { ships[sid]?.nowhp = s.maxhp }
                ndocks[i].state = 0; ndocks[i].complete = nil
                onTimersChanged?()
            }
        case "api_get_member/questlist":
            parseQuests(dict(d), tab: Int(req["api_tab_id"] ?? "") ?? 0)
        case "api_req_quest/clearitemget", "api_req_quest/stop":
            if let q = Int(req["api_quest_id"] ?? "") { quests[q] = nil }
        case "api_req_map/start":
            if let deck = Int(req["api_deck_id"] ?? "") {
                sortieDecks = [deck]
                // 連合艦隊は第1+第2艦隊
                if deck == 1, decks.count > 1, (Int(req["api_combined"] ?? "") ?? 0) > 0 { sortieDecks.insert(2) }
            }
        default:
            if path.hasPrefix("api_req_sortie/") || path.hasPrefix("api_req_battle_midnight/")
                || path.hasPrefix("api_req_combined_battle/"),
               !path.contains("battleresult"), !path.contains("goback_port") {
                applyBattle(dict(d))
            } else {
                return
            }
        }
        lastUpdate = Date()
    }

    // MARK: パース

    private func parseMaster(_ d: [String: Any]) {
        var m = MasterCache()
        for item in arr(d["api_mst_ship"]) {
            let s = dict(item)
            m.ships[int(s["api_id"])] = ShipMaster(
                name: s["api_name"] as? String ?? "?",
                fuel: int(s["api_fuel_max"]), bull: int(s["api_bull_max"]))
        }
        for item in arr(d["api_mst_mission"]) {
            let s = dict(item)
            let no = s["api_disp_no"] as? String ?? "\(int(s["api_id"]))"
            m.missions[int(s["api_id"])] = "\(no) \(s["api_name"] as? String ?? "")"
        }
        master = m
        if let data = try? JSONEncoder().encode(m) { try? data.write(to: Self.cacheURL) }
    }

    private func mergeShips(_ list: [Any]) {
        for item in list {
            let s = dict(item)
            let id = int(s["api_id"]); guard id > 0 else { continue }
            ships[id] = Ship(id: id,
                             masterId: int(s["api_ship_id"]),
                             lv: int(s["api_lv"]),
                             nowhp: int(s["api_nowhp"]),
                             maxhp: int(s["api_maxhp"]),
                             cond: int(s["api_cond"]),
                             fuel: int(s["api_fuel"]),
                             bull: int(s["api_bull"]))
        }
    }

    private func mergeDecks(_ list: [Any]) {
        guard !list.isEmpty else { return }
        for item in list {
            let deck = parseDeck(item)
            if let i = decks.firstIndex(where: { $0.id == deck.id }) { decks[i] = deck } else { decks.append(deck) }
        }
        decks.sort { $0.id < $1.id }
        onTimersChanged?()
    }

    private func parseDeck(_ item: Any) -> Deck {
        let d = dict(item)
        let m = arr(d["api_mission"]).map { int($0) }
        return Deck(id: int(d["api_id"]),
                    name: d["api_name"] as? String ?? "",
                    shipIds: arr(d["api_ship"]).map { int($0) }.filter { $0 > 0 },
                    missionState: m.count > 0 ? m[0] : 0,
                    missionId: m.count > 1 ? m[1] : 0,
                    missionEnd: m.count > 2 ? date(ms: m[2]) : nil)
    }

    private func parseDock(_ item: Any) -> DockInfo {
        let d = dict(item)
        return DockInfo(id: int(d["api_id"]), state: int(d["api_state"]),
                        shipId: int(d["api_ship_id"]), complete: date(ms: int(d["api_complete_time"])))
    }

    private func parseKdock(_ item: Any) -> DockInfo {
        let d = dict(item)
        return DockInfo(id: int(d["api_id"]), state: int(d["api_state"]),
                        shipId: int(d["api_created_ship_id"]), complete: date(ms: int(d["api_complete_time"])))
    }

    private func parseQuests(_ d: [String: Any], tab: Int) {
        var seen: [Int: Quest] = [:]
        var removed: Set<Int> = []
        for item in arr(d["api_list"]) {
            guard let q = item as? [String: Any] else { continue }
            let no = int(q["api_no"]), state = int(q["api_state"])
            if state == 2 || state == 3 {
                seen[no] = Quest(id: no, title: q["api_title"] as? String ?? "任務 \(no)",
                                 category: int(q["api_category"]), state: state,
                                 progress: int(q["api_progress_flag"]))
            } else {
                removed.insert(no)
            }
        }
        // 「遂行中」タブで1ページに収まっていれば、それを正として置き換え
        if tab == 9, int(d["api_page_count"]) <= 1 {
            quests = seen
        } else {
            for no in removed { quests[no] = nil }
            quests.merge(seen) { _, new in new }
        }
    }

    // MARK: 戦闘（被ダメージを計算して戦闘後のHPを予測）

    private func applyBattle(_ d: [String: Any]) {
        let deckId = int(d["api_deck_id"] ?? d["api_dock_id"])
        guard let main = decks.first(where: { $0.id == deckId }) else { return }
        var hpMain = arr(d["api_f_nowhps"]).map { int($0) }
        var hpEscort = arr(d["api_f_nowhps_combined"]).map { int($0) }
        let combined = !hpEscort.isEmpty
        guard !hpMain.isEmpty else { return }

        func hit(_ index: Int, _ dmg: Any?) {
            let v = Int(((dmg as? NSNumber)?.doubleValue ?? 0).rounded(.down))
            guard v > 0 else { return }
            if combined && index >= 6 {
                let i = index - 6
                if i < hpEscort.count { hpEscort[i] = max(0, hpEscort[i] - v) }
            } else if index < hpMain.count {
                hpMain[index] = max(0, hpMain[index] - v)
            }
        }
        func fdam(_ list: [Any], escort: Bool = false) {
            for (i, v) in list.enumerated() { hit(escort ? i + 6 : i, v) }
        }

        // 航空戦
        for key in ["api_injection_kouku", "api_kouku", "api_kouku2"] {
            let k = dict(d[key])
            fdam(arr(dict(k["api_stage3"])["api_fdam"]))
            if combined { fdam(arr(dict(k["api_stage3_combined"])["api_fdam"]), escort: true) }
        }
        // 雷撃戦
        for key in ["api_opening_atack", "api_raigeki"] {
            fdam(arr(dict(d[key])["api_fdam"]))
        }
        // 砲撃戦（敵の攻撃のみ）
        for key in ["api_opening_taisen", "api_hougeki1", "api_hougeki2", "api_hougeki3",
                    "api_hougeki", "api_n_hougeki1", "api_n_hougeki2"] {
            let h = dict(d[key])
            let flags = arr(h["api_at_eflag"]), targets = arr(h["api_df_list"]), damages = arr(h["api_damage"])
            for i in 0..<min(flags.count, targets.count, damages.count) where int(flags[i]) == 1 {
                let ts = arr(targets[i]), ds = arr(damages[i])
                for j in 0..<min(ts.count, ds.count) { hit(int(ts[j]), ds[j]) }
            }
        }

        var alert: [String] = []
        func update(_ deck: Deck, _ hps: [Int]) {
            for (i, sid) in deck.shipIds.enumerated() where i < hps.count {
                ships[sid]?.nowhp = hps[i]
                if let s = ships[sid], s.damage == .heavy { alert.append(name(of: s)) }
            }
        }
        update(main, hpMain)
        sortieDecks.insert(main.id)
        if combined, let escort = decks.first(where: { $0.id == 2 }) {
            update(escort, hpEscort)
            sortieDecks.insert(2)
        }
        heavyAlert = alert
    }

    // MARK: ユーティリティ

    private static func parseForm(_ s: String) -> [String: String] {
        var out: [String: String] = [:]
        for pair in s.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map {
                let r = String($0).replacingOccurrences(of: "+", with: " ")
                return r.removingPercentEncoding ?? r
            }
            if let k = kv.first { out[k] = kv.count > 1 ? kv[1] : "" }
        }
        return out
    }
}

private func int(_ v: Any?) -> Int {
    if let n = v as? NSNumber { return n.intValue }
    if let s = v as? String { return Int(s) ?? 0 }
    return 0
}
private func arr(_ v: Any?) -> [Any] { v as? [Any] ?? [] }
private func dict(_ v: Any?) -> [String: Any] { v as? [String: Any] ?? [:] }
private func date(ms: Int) -> Date? { ms > 0 ? Date(timeIntervalSince1970: Double(ms) / 1000) : nil }
