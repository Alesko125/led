package ua.ledpanel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun Context.bluetoothAdapter() = getSystemService(BluetoothManager::class.java)?.adapter

/** Шукає саме наш контролер (фільтр за MAC-адресою). Дозволи перевіряє викликач. */
@SuppressLint("MissingPermission")
suspend fun findController(ctx: Context, timeoutMs: Long): BluetoothDevice? {
    val scanner = ctx.bluetoothAdapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return null
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (cont.isActive) {
                        runCatching { scanner.stopScan(this) }
                        cont.resume(result.device)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            val filter = ScanFilter.Builder().setDeviceAddress(LedProtocol.ADDRESS).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, callback)
            cont.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
        }
    }
}

/**
 * Одне BLE-з'єднання з контролером. Усі операції — suspend і по черзі,
 * бо Android не дозволяє запускати наступну GATT-операцію, доки не завершилась попередня.
 */
@SuppressLint("MissingPermission")
class LedConnection(private val ctx: Context) {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var seq = 0
    private val rx = StringBuilder()
    private val lock = Mutex()

    @Volatile private var pendingOp: CompletableDeferred<Boolean>? = null
    @Volatile private var stateReply: CompletableDeferred<LedProtocol.State>? = null

    /** Завершується, коли з'єднання розірвано (контролер знеструмлено, вийшли з зони тощо). */
    val disconnected = CompletableDeferred<Unit>()

    suspend fun connect(device: BluetoothDevice): Boolean {
        var ok = false
        try {
            ok = withTimeoutOrNull(15_000) {
                step {
                    gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
                    gatt != null
                } && step { gatt!!.requestMtu(247) } &&
                    step { gatt!!.discoverServices() } &&
                    enableNotifications()
            } ?: false
        } finally {
            // І при скасуванні (екран закрили посеред підключення): інакше з'єднання висить відкритим,
            // контролер зайнятий і зникає з ефіру до перезапуску застосунку.
            if (!ok) close()
        }
        return ok
    }

    private suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val service = g.getService(LedProtocol.SERVICE) ?: return false
        val notify = service.getCharacteristic(LedProtocol.NOTIFY) ?: return false
        writeChar = service.getCharacteristic(LedProtocol.WRITE) ?: return false
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(LedProtocol.CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return step {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    suspend fun send(payload: ByteArray, expectsReply: Boolean = false): Boolean = lock.withLock {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        seq = (seq + 1) and 0xFF
        val packet = LedProtocol.wrap(payload, seq, expectsReply)
        withTimeoutOrNull(1_000) {
            step {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    c.value = packet
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            }
        } ?: false
    }

    suspend fun readState(): LedProtocol.State? {
        val reply = CompletableDeferred<LedProtocol.State>().also { stateReply = it }
        if (!send(LedProtocol.stateQuery(), expectsReply = true)) return null
        return withTimeoutOrNull(3_000) { reply.await() }?.also { powerSent = it.on }
    }

    // Яке живлення контролер уже отримав у цьому з'єднанні; null — невідомо.
    @Volatile private var powerSent: Boolean? = null

    /**
     * Виставляє вибраний стан. Команду живлення шлемо лише тоді, коли вона щось змінює:
     * раніше вона йшла з кожним рухом повзунка, і контролер захлинався пакетами.
     */
    suspend fun show(light: Light) {
        if (powerSent != light.on) {
            powerSent = if (send(LedProtocol.power(light.on))) light.on else null
            if (light.on) delay(100) // контролер не встигає прийняти колір одразу за увімкненням
        }
        if (light.on) send(LedProtocol.color(light.rgbw()))
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        pendingOp?.complete(false)
        if (!disconnected.isCompleted) Saved.markSeen(ctx)
        disconnected.complete(Unit)
    }

    private suspend fun step(start: () -> Boolean): Boolean {
        val op = CompletableDeferred<Boolean>().also { pendingOp = it }
        if (!start()) return false
        return op.await()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                pendingOp?.complete(true)
            } else {
                pendingOp?.complete(false)
                g.close()
                gatt = null
                if (!disconnected.isCompleted) Saved.markSeen(ctx)
                disconnected.complete(Unit)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pendingOp?.complete(true) // якщо MTU не збільшився — працюємо з типовим
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(true)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }

        @Deprecated("Android 12 і старші")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onData(c.value ?: return)
        }
    }

    /** Відповідь може прийти кількома шматками — збираємо до закритої дужки JSON. */
    private fun onData(bytes: ByteArray) {
        synchronized(rx) {
            rx.append(String(bytes, Charsets.ISO_8859_1))
            val start = rx.indexOf("{")
            val end = if (start >= 0) rx.indexOf("}", start) else -1
            if (end > start && start >= 0) {
                val json = rx.substring(start, end + 1)
                rx.setLength(0)
                LedProtocol.parseState(json)?.let { stateReply?.complete(it) }
            } else if (rx.length > 2048) {
                rx.setLength(0)
            }
        }
    }
}

/**
 * Перевіряє, чи контролер світить так, як збережено, і виправляє, якщо ні
 * (після скидання від стрибків живлення він починає миготіти або міняє колір).
 * Повертає вибір, який зараз діє.
 */
suspend fun LedConnection.restoreSaved(ctx: Context): Light? {
    val state = readState() ?: return null
    val saved = Saved.light(ctx) ?: return Light.from(state).also { Saved.save(ctx, it) }
    if (saved.matches(state)) return saved
    show(saved)
    delay(1_500)
    return saved
}
