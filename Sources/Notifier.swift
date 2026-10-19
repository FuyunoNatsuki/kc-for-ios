import Foundation
import UserNotifications

/// 遠征・入渠・建造の完了をローカル通知で知らせる
@MainActor
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()
    private let center = UNUserNotificationCenter.current()
    /// 遠征は帰還処理の時間を見て1分前に通知
    private let missionLead: TimeInterval = 60

    func setup() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func reschedule(_ game: GameState) {
        var items: [(String, String, String, Date)] = []
        for deck in game.decks where deck.missionState == 1 {
            if let end = deck.missionEnd {
                items.append(("mission-\(deck.id)", "遠征帰還",
                              "\(deck.name)：\(game.missionName(deck.missionId))", end.addingTimeInterval(-missionLead)))
            }
        }
        for dock in game.ndocks where dock.state == 1 {
            if let end = dock.complete {
                let name = game.ships[dock.shipId].map(game.name(of:)) ?? "艦娘"
                items.append(("ndock-\(dock.id)", "入渠完了", "第\(dock.id)ドック：\(name)", end))
            }
        }
        for dock in game.kdocks where dock.state == 2 {
            if let end = dock.complete {
                items.append(("kdock-\(dock.id)", "建造完了", "第\(dock.id)ドック", end))
            }
        }

        center.removeAllPendingNotificationRequests()
        let now = Date()
        for (id, title, body, date) in items where date > now {
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: date.timeIntervalSince(now), repeats: false)
            center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
        }
    }

    // アプリ表示中もバナーを出す
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
