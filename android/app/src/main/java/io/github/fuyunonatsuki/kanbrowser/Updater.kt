package io.github.fuyunonatsuki.kanbrowser

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

/** GitHub Releases の android-v* を見て、新しい版があればダウンロード・インストールする */
object Updater {
    private const val TAG_PREFIX = "android-v"

    /** バックグラウンドで最新版を確認し、新しければメインスレッドで callback を呼ぶ */
    fun check(onFound: (UpdateInfo) -> Unit, onNone: (() -> Unit)? = null) {
        Thread {
            val info = runCatching { fetchLatest() }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                if (info != null && info.versionCode > BuildConfig.VERSION_CODE) onFound(info) else onNone?.invoke()
            }
        }.start()
    }

    private fun fetchLatest(): UpdateInfo? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=30")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val list = JSONArray(text)
        var best: UpdateInfo? = null
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (!tag.startsWith(TAG_PREFIX)) continue
            val name = tag.removePrefix(TAG_PREFIX)                 // 1.0.N
            val code = name.substringAfterLast('.').toIntOrNull() ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            if (best == null || code > best.versionCode) {
                best = UpdateInfo(code, name, apk.optString("browser_download_url"), r.optString("body"))
            }
        }
        return best
    }

    fun download(context: Context, info: UpdateInfo) {
        // 提供元不明アプリのインストール許可がなければ設定画面へ
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "「この提供元のアプリを許可」をオンにしてから、もう一度更新してください", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
            return
        }
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "KanBrowser-${info.versionName}.apk")

        val dm = context.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("艦ブラ ${info.versionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file)))
        Toast.makeText(context, "ダウンロードしています…", Toast.LENGTH_SHORT).show()

        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val ok = dm.query(DownloadManager.Query().setFilterById(id)).use { cur ->
                    cur.moveToFirst() && cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
                }
                if (!ok || !file.exists()) {
                    Toast.makeText(app, "ダウンロードに失敗しました", Toast.LENGTH_LONG).show(); return
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                app.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // DownloadManager（システム）からの通知を受けるので exported
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }
}

/** 画面に出す更新の状態 */
class UpdateState {
    var info by androidx.compose.runtime.mutableStateOf<UpdateInfo?>(null)
    var upToDate by androidx.compose.runtime.mutableStateOf(false)
}
