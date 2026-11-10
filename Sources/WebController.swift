import SwiftUI
import WebKit
import UIKit

@MainActor
final class WebController: NSObject, ObservableObject {
    static let homeURL = URL(string: "https://play.games.dmm.com/game/kancolle")!
    /// DMMのスマホ用ページではなくPC版を表示させるためのUA
    static let desktopUA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    @Published var isLoading = false
    @Published var gameFound = false
    @Published var message: String?
    @Published var fitMode: Bool {
        didSet { UserDefaults.standard.set(fitMode, forKey: "fitMode"); applyFit() }
    }
    @Published var yOffset: Double {
        didSet { UserDefaults.standard.set(yOffset, forKey: "yOffset"); applyFit() }
    }
    /// フレームレートを30fpsに抑える（発熱による性能低下を防ぐ）。切り替えると再読み込み
    @Published var limitFPS: Bool {
        didSet {
            guard limitFPS != oldValue else { return }
            UserDefaults.standard.set(limitFPS, forKey: "limitFPS")
            installScripts(); webView.reload()
        }
    }
    @Published var showFPS: Bool {
        didSet { UserDefaults.standard.set(showFPS, forKey: "showFPS"); if !showFPS { fps = nil } }
    }
    /// ゲーム画面の実際のフレームレート（診断用）
    @Published private(set) var fps: Int?
    private var fpsReported = Date.distantPast

    let webView: WKWebView
    let game: GameState

    init(game: GameState) {
        self.game = game
        let d = UserDefaults.standard
        fitMode = d.object(forKey: "fitMode") as? Bool ?? true
        yOffset = d.double(forKey: "yOffset")
        limitFPS = d.object(forKey: "limitFPS") as? Bool ?? true
        showFPS = d.bool(forKey: "showFPS")

        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.websiteDataStore = .default()   // ログイン状態を保持

        config.userContentController = WKUserContentController()

        webView = WKWebView(frame: .zero, configuration: config)
        super.init()

        let uc = config.userContentController
        installScripts()
        uc.add(WeakHandler(self), name: "kanfit")
        uc.add(WeakHandler(self), name: "kanapi")
        uc.add(WeakHandler(self), name: "kanfps")

        webView.customUserAgent = Self.desktopUA
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.bounces = false
        webView.allowsBackForwardNavigationGestures = false
        goHome()
    }

    private func installScripts() {
        let uc = webView.configuration.userContentController
        uc.removeAllUserScripts()
        uc.addUserScript(WKUserScript(source: Scripts.apiCapture(cap: limitFPS ? 30 : 0),
                                      injectionTime: .atDocumentStart, forMainFrameOnly: false))
        uc.addUserScript(WKUserScript(source: Scripts.allFrames, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        uc.addUserScript(WKUserScript(source: Scripts.mainFrame, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
    }

    // MARK: - Actions

    func goHome() { webView.load(URLRequest(url: Self.homeURL)) }

    func reload() { webView.reload() }

    func applyFit() {
        let js = "window.__kanFit && window.__kanFit(\(fitMode ? "true" : "false"), \(yOffset));"
        webView.evaluateJavaScript(js, completionHandler: nil)
        updateScrollLock()
    }

    func snapshot() {
        webView.takeSnapshot(with: nil) { [weak self] image, _ in
            guard let image else { self?.message = "スクリーンショットに失敗しました"; return }
            UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
            self?.message = "写真に保存しました"
        }
    }

    func clearAllData() {
        let store = WKWebsiteDataStore.default()
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) { [weak self] in
            self?.message = "Cookie・キャッシュを消去しました"
            self?.goHome()
        }
    }

    private func updateScrollLock() {
        let lock = fitMode && gameFound
        webView.scrollView.isScrollEnabled = !lock
        webView.scrollView.pinchGestureRecognizer?.isEnabled = !lock
        if lock { webView.scrollView.setZoomScale(1, animated: false); webView.scrollView.contentOffset = .zero }
    }

    fileprivate func receive(_ message: WKScriptMessage) {
        switch message.name {
        case "kanfit":
            gameFound = (message.body as? Bool) ?? false
            updateScrollLock()
        case "kanfps":
            // 複数のフレームから届くので、2秒以内は大きい方（＝ゲーム本体）を採用
            guard showFPS, let v = message.body as? Int else { return }
            let now = Date()
            if now.timeIntervalSince(fpsReported) > 2.5 || v > (fps ?? 0) { fps = v; fpsReported = now }
        case "kanapi":
            guard let m = message.body as? [String: Any],
                  let path = m["path"] as? String, let text = m["text"] as? String else { return }
            game.handle(path: path, body: m["body"] as? String ?? "", text: text)
        default:
            break
        }
    }
}

// MARK: - WKNavigationDelegate / WKUIDelegate

extension WebController: WKNavigationDelegate, WKUIDelegate {
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        isLoading = true
        gameFound = false
        updateScrollLock()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        isLoading = false
        applyFit()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        isLoading = false
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        isLoading = false
        if (error as NSError).code != NSURLErrorCancelled {
            message = "読み込みに失敗しました：\(error.localizedDescription)"
        }
    }

    /// メモリ不足でWebプロセスが落ちたら自動で再読み込み
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        message = "ページが停止したため再読み込みします"
        webView.reload()
    }

    /// target=_blank / window.open は同じ画面で開く
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, navigationAction.request.url != nil {
            webView.load(navigationAction.request)
        }
        return nil
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        present(UIAlertController(title: nil, message: message, preferredStyle: .alert),
                actions: [UIAlertAction(title: "OK", style: .default) { _ in completionHandler() }])
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        present(UIAlertController(title: nil, message: message, preferredStyle: .alert), actions: [
            UIAlertAction(title: "キャンセル", style: .cancel) { _ in completionHandler(false) },
            UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) }
        ])
    }

    private func present(_ alert: UIAlertController, actions: [UIAlertAction]) {
        actions.forEach(alert.addAction)
        var top = webView.window?.rootViewController
        while let p = top?.presentedViewController { top = p }
        top?.present(alert, animated: true)
    }
}

/// WKUserContentController の循環参照を避けるための弱参照ハンドラ
@MainActor
private final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: WebController?
    init(_ target: WebController) { self.target = target }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.receive(message)
    }
}

struct GameWebView: UIViewRepresentable {
    let controller: WebController
    func makeUIView(context: Context) -> WKWebView { controller.webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
