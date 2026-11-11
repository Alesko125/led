package ua.ledpanel

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

/**
 * Фонове відновлення: Android сам стежить за появою нашого контролера в ефірі
 * (апаратний фільтр за MAC — майже не витрачає батарею). Коли машину завели і контролер
 * з'явився, застосунок ненадовго підключається, перевіряє колір і виправляє, якщо він збився.
 * Застосунок для цього відкривати не треба.
 */
object AutoRestore {
    private const val WORK = "restore"

    /**
     * Скільки контролер має мовчати, щоб його появу вважати запуском машини. Поки він живий,
     * Android бачить його кожні кілька секунд, тож хвилини вистачає й на коротку зупинку біля магазину.
     */
    const val ABSENCE_MS = 60_000L

    // Перереєстрація сканування зайвий раз з'їдає ліміт Android (5 запусків за 30 с).
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        // На Android 11 і старіших фонове сканування вимагає дозволу на геолокацію у фоні — там не вмикаємо.
        if (Build.VERSION.SDK_INT < 31) return
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        val scanner = ctx.bluetoothAdapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        registerScan(scanner, ctx)
    }

    @SuppressLint("MissingPermission")
    private fun registerScan(scanner: BluetoothLeScanner, ctx: Context) {
        val intent = pendingIntent(ctx)
        runCatching { scanner.stopScan(intent) }
        val filter = ScanFilter.Builder().setDeviceAddress(LedProtocol.ADDRESS).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        registered = scanner.startScan(listOf(filter), settings, intent) == 0
    }

    /** Екран відкрили — фонова перевірка не повинна змагатися з ним за єдине з'єднання. */
    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ScanReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RestoreWorker>().build(),
        )
    }
}

/**
 * Android будить його щоразу, як бачить контролер. Перевіряємо лише тоді, коли контролера
 * не було в ефірі кілька хвилин, тобто машину справді щойно завели.
 */
class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.hasExtra("android.bluetooth.le.extra.ERROR_CODE")) return
        if (!Saved.appearedAfterGap(ctx, AutoRestore.ABSENCE_MS)) return
        if (MainActivity.isVisible) return // екран відкритий — він і так тримає з'єднання
        AutoRestore.schedule(ctx)
    }
}

/** Після перезавантаження телефона стеження треба ввімкнути знову. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AutoRestore.register(ctx)
    }
}

/**
 * Перевіряє контролер тричі з інтервалом 15 с: стрибки напруги під час запуску двигуна
 * можуть скинути його вже після першої перевірки.
 */
class RestoreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        repeat(3) { attempt ->
            if (attempt > 0) delay(15_000)
            if (!MainActivity.isVisible) checkOnce()
        }
        return Result.success()
    }

    private suspend fun checkOnce() {
        if (Saved.light(applicationContext) == null) return
        val device = findController(applicationContext, 10_000) ?: return
        val conn = LedConnection(applicationContext)
        try {
            if (conn.connect(device)) conn.restoreSaved(applicationContext)
        } finally {
            conn.close() // звільняємо контролер: він приймає лише одне з'єднання
        }
    }
}
