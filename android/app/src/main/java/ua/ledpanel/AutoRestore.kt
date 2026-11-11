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

    fun register(ctx: Context) {
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
        scanner.startScan(listOf(filter), settings, intent)
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

/** Android будить його, щойно бачить контролер. Поки машина працює — не частіше разу на хвилину. */
class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.hasExtra("android.bluetooth.le.extra.ERROR_CODE")) return
        if (MainActivity.isVisible) return // екран відкритий — він і так тримає з'єднання
        if (!Saved.claimBackgroundCheck(ctx, 60_000)) return
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
