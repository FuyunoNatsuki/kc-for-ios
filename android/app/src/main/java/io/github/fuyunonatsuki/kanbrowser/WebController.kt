package io.github.fuyunonatsuki.kanbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** WebView とゲーム状態をまとめて管理する */
class WebController(private val activity: Activity, val game: GameState) {
    companion object {
        const val HOME_URL = "https://play.games.dmm.com/game/kancolle"
        /** DMMのスマホ用ページではなくPC版を表示させるためのUA */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }

    private val prefs = activity.getSharedPreferences("settings", Activity.MODE_PRIVATE)
    var isLoading by mutableStateOf(false); private set
    var gameFound by mutableStateOf(false); private set
    var fitMode by mutableStateOf(prefs.getBoolean("fitMode", true)); private set
    var yOffset by mutableStateOf(prefs.getInt("yOffset", 0)); private set
    var onRendererGone: (() -> Unit)? = null

    private val fitJs = activity.assets.open("fit.js").bufferedReader().use { it.readText() }
    private val startJs = activity.assets.open("start.js").bufferedReader().use { it.readText() }

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(activity).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = WebView.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)       // target=_blank は同じ画面で開く
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@apply, true)   // ゲームはDMMのiframe内で動くため必須
        }
    }

    init {
        val origins = setOf("*")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // 全フレーム（ゲーム本体の iframe 含む）にページ読み込み前に注入
            WebViewCompat.addDocumentStartJavaScript(webView, startJs, origins)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "kanbridge", origins) { _, message, _, _, _ ->
                receive(message.data ?: return@addWebMessageListener)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                isLoading = true
                gameFound = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                isLoading = false
                CookieManager.getInstance().flush()
                applyFit()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    isLoading = false
                    toast("読み込みに失敗しました：${error.description}")
                }
            }

            /** メモリ不足などでWebの描画プロセスが落ちたら作り直す */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                toast("ページが停止したため再読み込みします")
                onRendererGone?.invoke()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                android.app.AlertDialog.Builder(activity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("キャンセル") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
    }

    // MARK: 受信

    private fun receive(data: String) {
        val m = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (m.optString("type")) {
            "fit" -> gameFound = m.optBoolean("found")
            "api" -> game.handle(m.optString("path"), m.optString("body"), m.optString("text"))
        }
    }

    // MARK: 操作

    fun goHome() = webView.loadUrl(HOME_URL)

    fun reload() = webView.reload()

    fun setFit(on: Boolean) {
        fitMode = on; prefs.edit().putBoolean("fitMode", on).apply(); applyFit()
    }

    fun setOffset(v: Int) {
        yOffset = v; prefs.edit().putInt("yOffset", v).apply(); applyFit()
    }

    fun applyFit() {
        webView.evaluateJavascript("$fitJs\nwindow.__kanFit && window.__kanFit($fitMode, $yOffset);", null)
    }

    fun snapshot() {
        val w = webView.width; val h = webView.height
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2).also { webView.getLocationInWindow(it) }
        val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        // WebGL の描画も含めて撮るため、画面そのものをコピーする
        PixelCopy.request(activity.window, rect, bmp, { result ->
            if (result == PixelCopy.SUCCESS) save(bmp) else toast("スクリーンショットに失敗しました")
        }, Handler(Looper.getMainLooper()))
    }

    private fun save(bmp: Bitmap) {
        val name = "KanBrowser_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KanBrowser")
            }
        }
        val uri: Uri? = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val ok = uri != null && runCatching {
            activity.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
        }.getOrDefault(false)
        toast(if (ok) "写真に保存しました（Pictures/KanBrowser）" else "保存に失敗しました")
    }

    fun clearAllData() {
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            toast("Cookie・キャッシュを消去しました")
            goHome()
        }
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()
}
