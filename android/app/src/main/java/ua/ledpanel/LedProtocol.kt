package ua.ledpanel

import java.util.UUID

/** Протокол контролера LEDnetWF (Magic Home) по Bluetooth LE. */
object LedProtocol {
    val SERVICE: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Контролер у машині тестя. Застосунок працює лише з ним. */
    const val ADDRESS = "B4:E8:42:E9:B6:1D"

    /** Режими «просто горить кольором». Решта — ефекти, зокрема миготіння після скидання. */
    private val STATIC_MODES = setOf(0x61, 0x63)

    data class State(val on: Boolean, val mode: Int, val r: Int, val g: Int, val b: Int, val w: Int) {
        val isStatic: Boolean get() = mode in STATIC_MODES
    }

    private fun withChecksum(vararg bytes: Int): ByteArray =
        ByteArray(bytes.size + 1) { i -> (if (i < bytes.size) bytes[i] else bytes.sum()).toByte() }

    /** Нові контролери чекають команду в обгортці: 00 seq 80 00 00 len len+1 type payload. */
    fun wrap(payload: ByteArray, seq: Int, expectsReply: Boolean): ByteArray = byteArrayOf(
        0, seq.toByte(), 0x80.toByte(), 0, 0,
        payload.size.toByte(), (payload.size + 1).toByte(),
        (if (expectsReply) 0x0A else 0x0B).toByte(),
    ) + payload

    fun stateQuery() = withChecksum(0x81, 0x8A, 0x8B)
    fun power(on: Boolean) = withChecksum(0x71, if (on) 0x23 else 0x24, 0x0F)
    fun color(rgbw: IntArray) = withChecksum(0x31, rgbw[0], rgbw[1], rgbw[2], rgbw[3], 0x00, 0x0F)

    private val payloadRegex = Regex("\"payload\"\\s*:\\s*\"([0-9A-Fa-f]+)\"")

    /** Відповідь приходить як JSON: {"code":0,"payload":"8106 23 61 ..."}. */
    fun parseState(json: String): State? {
        val hex = payloadRegex.find(json)?.groupValues?.get(1) ?: return null
        val raw = hex.chunked(2).map { it.toInt(16) }
        if (raw.size < 10 || raw[0] != 0x81) return null
        return State(on = raw[2] == 0x23, mode = raw[3], r = raw[6], g = raw[7], b = raw[8], w = raw[9])
    }
}
