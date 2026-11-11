package ua.ledpanel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Тримає з'єднання, поки відкритий екран, і сам перепідключається, якщо воно рветься. */
class LedController(private val ctx: Context, private val scope: CoroutineScope) {

    enum class Status { BluetoothOff, Searching, Connecting, Connected }

    private val _status = MutableStateFlow(Status.Searching)
    val status: StateFlow<Status> = _status

    /** Стан, який діє на контролері після підключення (уже виправлений, якщо він збився). */
    private val _connectedLight = MutableStateFlow<Light?>(null)
    val connectedLight: StateFlow<Light?> = _connectedLight

    private var loop: Job? = null
    private var conn: LedConnection? = null

    // Під час руху повзунка надсилаємо лише найсвіжіше значення, а не всі проміжні.
    private var pending: Light? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val light = pending ?: break
                    pending = null
                    conn?.show(light)
                    delay(40)
                }
            }
        }
    }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        conn?.close()
        conn = null
    }

    fun set(light: Light) {
        pending = light
        wake.trySend(Unit)
    }

    private suspend fun runLoop() {
        while (true) {
            if (ctx.bluetoothAdapter()?.isEnabled != true) {
                _status.value = Status.BluetoothOff
                delay(1_000)
                continue
            }
            _status.value = Status.Searching
            val device = findController(ctx, 15_000) ?: continue

            _status.value = Status.Connecting
            val c = LedConnection(ctx)
            if (!c.connect(device)) {
                delay(1_000)
                continue
            }
            conn = c
            _connectedLight.value = c.restoreSaved(ctx)
            _status.value = Status.Connected
            wake.trySend(Unit)

            c.disconnected.await()
            conn = null
            _status.value = Status.Searching
            delay(500)
        }
    }
}
