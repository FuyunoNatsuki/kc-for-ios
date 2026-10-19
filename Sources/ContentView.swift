import SwiftUI

struct ContentView: View {
    @StateObject private var web = WebController(game: GameState())
    @State private var showOffset = false
    @State private var confirmClear = false
    @State private var showPanel = false

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Color.black.ignoresSafeArea()

                GameWebView(controller: web)
                    .ignoresSafeArea()

                if web.isLoading {
                    ProgressView()
                        .tint(.white)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .allowsHitTesting(false)
                }

                // 画面左端（ゲーム画面の外の黒帯）に置くボタン
                VStack(spacing: 14) {
                    menu
                    PanelButton(game: web.game, isOpen: showPanel) {
                        withAnimation(.easeOut(duration: 0.2)) { showPanel.toggle() }
                    }
                }
                .padding(.leading, 4)

                if showPanel {
                    StatusPanel(game: web.game) {
                        withAnimation(.easeOut(duration: 0.2)) { showPanel = false }
                    }
                    .frame(width: min(440, geo.size.width * 0.5))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    .transition(.move(edge: .trailing))
                }

                HeavyDamageBanner(game: web.game)
            }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .defersSystemGestures(on: .all)   // 端スワイプの誤爆を防ぐ
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true   // 自動ロックしない
            Notifier.shared.setup()
            let game = web.game
            game.onTimersChanged = { [weak game] in
                guard let game else { return }
                Notifier.shared.reschedule(game)
            }
        }
        .sheet(isPresented: $showOffset) { OffsetSheet(web: web) }
        .confirmationDialog("Cookieとキャッシュをすべて消去しますか？", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("消去する", role: .destructive) { web.clearAllData() }
        }
        .alert(web.message ?? "", isPresented: Binding(
            get: { web.message != nil },
            set: { if !$0 { web.message = nil } }
        )) {
            Button("OK", role: .cancel) {}
        }
    }

    private var menu: some View {
        Menu {
            Button { web.reload() } label: { Label("再読み込み", systemImage: "arrow.clockwise") }
            Button { web.goHome() } label: { Label("ゲームページへ", systemImage: "house") }
            Toggle(isOn: $web.fitMode) { Label("画面にフィット", systemImage: "arrow.up.left.and.arrow.down.right") }
            Button { showOffset = true } label: { Label("表示位置の微調整", systemImage: "slider.vertical.3") }
            Button { web.snapshot() } label: { Label("スクリーンショット", systemImage: "camera") }
            Divider()
            Button(role: .destructive) { confirmClear = true } label: {
                Label("ログアウト（データ消去）", systemImage: "trash")
            }
        } label: {
            Image(systemName: "line.3.horizontal.circle.fill")
                .font(.system(size: 28))
                .foregroundStyle(.white.opacity(0.35))
                .padding(8)
                .contentShape(Rectangle())
        }
    }
}

/// 情報パネルの開閉ボタン。出撃中に大破艦がいると赤くなる
struct PanelButton: View {
    @ObservedObject var game: GameState
    let isOpen: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isOpen ? "xmark.circle.fill" : "list.bullet.circle.fill")
                .font(.system(size: 28))
                .foregroundStyle(game.hasHeavyInSortie ? Color.red : Color.white.opacity(0.35))
                .padding(8)
                .contentShape(Rectangle())
        }
    }
}

/// 戦闘後に大破艦がいたら上部に警告を出す（タップで閉じる）
struct HeavyDamageBanner: View {
    @ObservedObject var game: GameState

    var body: some View {
        if !game.heavyAlert.isEmpty {
            VStack {
                Button { game.heavyAlert = [] } label: {
                    Label("大破：\(game.heavyAlert.joined(separator: "、"))", systemImage: "exclamationmark.triangle.fill")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16).padding(.vertical, 8)
                        .background(Color.red.opacity(0.9), in: Capsule())
                }
                .padding(.top, 8)
                Spacer()
            }
            .frame(maxWidth: .infinity)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

struct OffsetSheet: View {
    @ObservedObject var web: WebController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Stepper(value: $web.yOffset, in: -60...60, step: 1) {
                        Text("縦オフセット：\(Int(web.yOffset)) px")
                    }
                    Button("リセット") { web.yOffset = 0 }
                } footer: {
                    Text("ゲーム画面の上端に白い帯や切れが出る場合に調整してください。プラスで上にずらします。")
                }
            }
            .navigationTitle("表示位置の微調整")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完了") { dismiss() } } }
        }
        .presentationDetents([.medium])
    }
}
