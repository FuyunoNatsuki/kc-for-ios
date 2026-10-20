package io.github.fuyunonatsuki.kanbrowser

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 遠征・入渠・建造の完了をアラームで通知する */
object Notifier {
    private const val CHANNEL = "timers"
    /** 遠征は帰還処理の時間を見て1分前に通知 */
    private const val MISSION_LEAD_MS = 60_000L
    /** 予約に使う固定のリクエストコード（遠征2〜4 / 入渠1〜4 / 建造1〜4） */
    private val slots = (2..4).map { "mission-$it" } + (1..4).map { "ndock-$it" } + (1..4).map { "kdock-$it" }

    fun setup(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "遠征・入渠・建造", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun reschedule(context: Context, game: GameState) {
        val items = mutableListOf<Triple<String, Pair<String, String>, Long>>()
        for (deck in game.decks) if (deck.missionState == 1 && deck.missionEnd > 0) {
            items += Triple("mission-${deck.id}",
                "遠征帰還" to "${deck.name}：${game.missionName(deck.missionId)}", deck.missionEnd - MISSION_LEAD_MS)
        }
        for (dock in game.ndocks) if (dock.state == 1 && dock.complete > 0) {
            val name = game.ships[dock.shipId]?.let(game::name) ?: "艦娘"
            items += Triple("ndock-${dock.id}", "入渠完了" to "第${dock.id}ドック：$name", dock.complete)
        }
        for (dock in game.kdocks) if (dock.state == 2 && dock.complete > 0) {
            items += Triple("kdock-${dock.id}", "建造完了" to "第${dock.id}ドック", dock.complete)
        }

        val am = context.getSystemService(AlarmManager::class.java)
        slots.forEach { am.cancel(pending(context, it, "", "")) }
        val now = System.currentTimeMillis()
        for ((key, text, at) in items) {
            if (at <= now || key !in slots) continue
            val pi = pending(context, key, text.first, text.second)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(context: Context, key: String, title: String, body: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra("title", title).putExtra("body", body).putExtra("id", slots.indexOf(key))
        return PendingIntent.getBroadcast(context, slots.indexOf(key), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(context: Context, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(100 + id, n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.setup(context)
        Notifier.show(context, intent.getIntExtra("id", 0),
            intent.getStringExtra("title") ?: "艦ブラ", intent.getStringExtra("body") ?: "")
    }
}
