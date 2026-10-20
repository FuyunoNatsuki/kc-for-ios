package io.github.fuyunonatsuki.kanbrowser

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var web: WebController
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 自動で画面を消さない
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        Notifier.setup(this)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val game = GameState(this)
        game.onTimersChanged = { Notifier.reschedule(this, game) }
        web = WebController(this, game)
        web.onRendererGone = { recreate() }
        web.goHome()

        setContent {
            MainScreen(
                web = web,
                update = update,
                onCheckUpdate = { checkUpdate(manual = true) },
                onInstallUpdate = { Updater.download(this, it); update.info = null },
                onExactAlarm = { openExactAlarmSettings() },
            )
        }

        // 戻るボタン：ページを戻る。戻れなければアプリをバックグラウンドへ（誤終了防止）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.webView.canGoBack()) web.webView.goBack() else moveTaskToBack(true)
            }
        })

        checkUpdate(manual = false)
    }

    private val update = UpdateState()

    private fun checkUpdate(manual: Boolean) {
        Updater.check(
            onFound = { update.info = it },
            onNone = { if (manual) update.upToDate = true },
        )
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        web.webView.onResume()
    }

    override fun onPause() {
        // バックグラウンドでもゲームの音や通信を止めないよう、WebView は一時停止しない
        super.onPause()
    }

    override fun onDestroy() {
        (web.webView.parent as? android.view.ViewGroup)?.removeView(web.webView)
        web.webView.destroy()
        super.onDestroy()
    }
}
