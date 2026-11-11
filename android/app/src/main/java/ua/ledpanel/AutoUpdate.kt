package ua.ledpanel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Оновлення з GitHub Releases: застосунок сам дивиться останній реліз і ставить новий APK.
 * Тег релізу — `v<versionCode>`, наприклад v3, а в релізі один файл .apk.
 * Перший раз Android попросить дозволити встановлення з цього застосунку,
 * далі (Android 12+) оновлення ставляться без запитань.
 */
object AutoUpdate {
    const val LATEST ="https://api.github.com/repos/Alesko125/led/releases/latest"
    private const val PERIODIC = "update"
    private const val NOW = "update-now"
    const val CHANNEL = "update"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Перевірка раз на 12 годин, поки є інтернет. */
    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS).setConstraints(online).build(),
        )
    }

    /** Позачергова перевірка при відкритті застосунку. */
    fun checkNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(online).build(),
        )
    }

    fun installedVersion(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private class NotFound : Exception()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Періодична й позачергова перевірки можуть збігтися — працюють по черзі з одним файлом.
        lock.withLock {
            try {
                update()
                Result.success()
            } catch (e: NotFound) {
                Result.success() // релізів ще немає
            } catch (e: IOException) {
                if (runAttemptCount < 3) Result.retry() else Result.success() // мережа: спробуємо ще, але не вічно
            } catch (e: Exception) {
                Result.success() // зламана відповідь чи встановлювач: наступна планова перевірка спробує знову
            }
        }
    }

    private fun update() {
        val ctx = applicationContext
        val release = JSONObject(get(URL(AutoUpdate.LATEST)))
        val tag = release.getString("tag_name")
        val version = tag.removePrefix("v").toLongOrNull() ?: return
        if (version <= AutoUpdate.installedVersion(ctx)) return
        val prefs = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (prefs.getString("rejected", null) == tag) return // цей APK уже виявився не нашим — не качаємо знову

        val assets = release.getJSONArray("assets")
        val url = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?.getString("browser_download_url") ?: return

        val apk = File(ctx.cacheDir, "update-$version.apk")
        if (!apk.exists()) {
            ctx.cacheDir.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }
            download(URL(url), apk)
        }
        if (!isOurNewerApk(apk)) {
            apk.delete()
            prefs.edit().putString("rejected", tag).apply()
            return
        }
        // Встановлення закриває застосунок, тож поки людина ним користується, лише завантажуємо.
        // Поставимо, щойно екран закриють (MainActivity.onStop запускає перевірку знову).
        if (MainActivity.isVisible) return
        install(apk)
    }

    /** Ставимо лише свій пакет і лише новішу версію; підпис Android перевірить сам. */
    private fun isOurNewerApk(apk: File): Boolean {
        val ctx = applicationContext
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        return info.packageName == ctx.packageName &&
            PackageInfoCompat.getLongVersionCode(info) > AutoUpdate.installedVersion(ctx)
    }

    private fun install(apk: File) {
        val ctx = applicationContext
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        // Попередня спроба могла зависнути в очікуванні підтвердження — прибираємо, щоб копії APK не накопичувались.
        installer.mySessions.filter { it.appPackageName == ctx.packageName }
            .forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    private fun get(url: URL): String = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun download(url: URL, to: File) {
        val tmp = File(to.path + ".part")
        open(url).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(to)
    }

    private fun open(url: URL): HttpURLConnection {
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "ledpanel-updater")
        val code = c.responseCode
        if (code == 404) throw NotFound()
        if (code !in 200..299) throw IOException("HTTP $code")
        return c
    }

    private companion object {
        val lock = Mutex()
    }
}

/**
 * Відповідь встановлювача. Якщо Android хоче підтвердження (перше оновлення або дозвіл
 * на встановлення), показуємо його одразу, коли застосунок відкритий, інакше — сповіщенням.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (MainActivity.isVisible) {
            ctx.startActivity(confirm)
        } else {
            notify(ctx, confirm)
        }
    }

    private fun notify(ctx: Context, confirm: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return // без дозволу покажемо, коли застосунок відкриють: він перевіряє оновлення на старті
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(AutoUpdate.CHANNEL, "Оновлення", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val tap = PendingIntent.getActivity(ctx, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(ctx, AutoUpdate.CHANNEL)
        } else {
            android.app.Notification.Builder(ctx)
        }
        nm.notify(
            1,
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Підсвітка: є оновлення")
                .setContentText("Торкніться, щоб встановити")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }
}
