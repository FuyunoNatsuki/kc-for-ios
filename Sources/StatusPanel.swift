import SwiftUI

/// タップで開く情報パネル（艦隊 / 遠征・ドック / 任務）
struct StatusPanel: View {
    @ObservedObject var game: GameState
    let close: () -> Void

    enum Tab: String, CaseIterable { case fleet = "艦隊", timers = "遠征・ドック", quests = "任務" }
    @State private var tab: Tab = .fleet
    @State private var deckId = 1

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Picker("", selection: $tab) {
                    ForEach(Tab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                Button(action: close) {
                    Image(systemName: "xmark").font(.headline).padding(6)
                }
            }

            if game.lastUpdate == nil {
                Spacer()
                Text("ゲームにログインして母港を開くと表示されます")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Spacer()
            } else {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    ScrollView {
                        switch tab {
                        case .fleet: fleetView
                        case .timers: timersView(now: context.date)
                        case .quests: questsView
                        }
                    }
                }
            }
        }
        .padding(10)
        .background(.ultraThinMaterial)
        .environment(\.colorScheme, .dark)
    }

    // MARK: 艦隊

    private var fleetView: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !game.decks.isEmpty {
                Picker("", selection: $deckId) {
                    ForEach(game.decks) { d in Text("第\(d.id)").tag(d.id) }
                }
                .pickerStyle(.segmented)
            }
            if let deck = game.decks.first(where: { $0.id == deckId }) {
                HStack {
                    Text(deck.name).font(.subheadline.bold())
                    if game.sortieDecks.contains(deck.id) { tag("出撃中", .blue) }
                    if deck.missionState == 1 { tag("遠征中", .teal) }
                    Spacer()
                    let ships = game.members(of: deck)
                    if !ships.isEmpty {
                        Text("最低cond \(ships.map(\.cond).min() ?? 0)")
                            .font(.caption).foregroundStyle(condColor(ships.map(\.cond).min() ?? 49))
                    }
                }
                ForEach(game.members(of: deck)) { ShipRow(game: game, ship: $0) }
            }
        }
    }

    // MARK: 遠征・ドック

    private func timersView(now: Date) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            section("遠征") {
                ForEach(game.decks.filter { $0.id > 1 }) { d in
                    timerRow(title: "第\(d.id)艦隊",
                             detail: d.missionState == 0 ? "待機中" : game.missionName(d.missionId),
                             end: d.missionState == 0 ? nil : d.missionEnd, now: now)
                }
            }
            section("入渠") {
                ForEach(game.ndocks.filter { $0.state >= 0 }) { d in
                    timerRow(title: "第\(d.id)ドック",
                             detail: d.state == 1 ? (game.ships[d.shipId].map(game.name(of:)) ?? "") : "空き",
                             end: d.state == 1 ? d.complete : nil, now: now)
                }
            }
            if !game.kdocks.isEmpty {
                section("建造") {
                    ForEach(game.kdocks.filter { $0.state >= 0 }) { d in
                        timerRow(title: "第\(d.id)ドック",
                                 detail: d.state == 0 ? "空き" : game.shipName(masterId: d.shipId),
                                 end: d.state == 2 ? d.complete : nil, now: now,
                                 doneText: d.state == 3 ? "完成" : nil)
                    }
                }
            }
        }
    }

    private func timerRow(title: String, detail: String, end: Date?, now: Date, doneText: String? = nil) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(detail).font(.subheadline).lineLimit(1)
            }
            Spacer()
            if let end {
                let left = end.timeIntervalSince(now)
                Text(left > 0 ? Self.format(left) : "完了")
                    .font(.body.monospacedDigit().bold())
                    .foregroundStyle(left <= 0 ? .green : left < 60 ? .yellow : .primary)
            } else if let doneText {
                Text(doneText).font(.body.bold()).foregroundStyle(.green)
            }
        }
    }

    // MARK: 任務

    private var questsView: some View {
        VStack(alignment: .leading, spacing: 6) {
            let list = game.quests.values.sorted { ($0.category, $0.id) < ($1.category, $1.id) }
            if list.isEmpty {
                Text("ゲーム内で任務画面を開くと、受注中の任務が表示されます")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(list) { q in
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 2).fill(categoryColor(q.category)).frame(width: 4)
                    Text(q.title).font(.subheadline).lineLimit(2)
                    Spacer()
                    Text(q.state == 3 ? "達成" : q.progress == 2 ? "80%" : q.progress == 1 ? "50%" : "")
                        .font(.caption.bold())
                        .foregroundStyle(q.state == 3 ? .green : .secondary)
                }
                .padding(.vertical, 2)
            }
            Text("任務画面で表示したページの分だけ反映されます")
                .font(.caption2).foregroundStyle(.secondary).padding(.top, 4)
        }
    }

    // MARK: 部品

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.footnote.bold()).foregroundStyle(.secondary)
            content()
        }
    }

    private func tag(_ text: String, _ color: Color) -> some View {
        Text(text).font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2)
            .background(color.opacity(0.35), in: Capsule())
    }

    private func categoryColor(_ c: Int) -> Color {
        switch c {
        case 1: return .green        // 編成
        case 2, 8, 9: return .red    // 出撃
        case 3: return .mint         // 演習
        case 4: return .cyan         // 遠征
        case 5: return .yellow       // 補給・入渠
        case 6: return .brown        // 工廠
        case 7: return .purple       // 改装
        default: return .gray
        }
    }

    static func format(_ t: TimeInterval) -> String {
        let s = Int(t.rounded(.up))
        return String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
}

func condColor(_ cond: Int) -> Color {
    cond >= 50 ? .yellow : cond >= 30 ? .primary : cond >= 20 ? .orange : .red
}

struct ShipRow: View {
    @ObservedObject var game: GameState
    let ship: Ship

    var body: some View {
        let dmg = ship.damage
        let fMax = game.fuelMax(ship), bMax = game.bullMax(ship)
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(game.name(of: ship)).font(.subheadline.bold()).lineLimit(1)
                Text("Lv\(ship.lv)").font(.caption).foregroundStyle(.secondary)
                if !dmg.label.isEmpty {
                    Text(dmg.label).font(.caption2.bold()).foregroundStyle(.white)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(dmg.color, in: Capsule())
                }
                Spacer()
                Image(systemName: ship.cond >= 50 ? "sparkles" : "face.smiling")
                    .font(.caption).foregroundStyle(condColor(ship.cond))
                Text("\(ship.cond)").font(.subheadline.monospacedDigit().bold()).foregroundStyle(condColor(ship.cond))
            }
            HStack(spacing: 6) {
                ProgressView(value: Double(ship.nowhp), total: Double(max(ship.maxhp, 1)))
                    .tint(dmg.color)
                Text("\(ship.nowhp)/\(ship.maxhp)").font(.caption.monospacedDigit()).frame(width: 56, alignment: .trailing)
                if fMax > 0 && (ship.fuel < fMax || ship.bull < bMax) {
                    Image(systemName: "fuelpump.fill").font(.caption2).foregroundStyle(.orange)
                }
            }
        }
        .padding(8)
        .background(dmg == .heavy ? Color.red.opacity(0.25) : Color.white.opacity(0.06),
                    in: RoundedRectangle(cornerRadius: 8))
    }
}
