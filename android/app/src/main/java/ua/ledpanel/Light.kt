package ua.ledpanel

import android.content.Context
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Вибір користувача: колір (null — окремий білий канал) і яскравість 0.05..1. */
data class Light(val on: Boolean = true, val hue: Float? = null, val brightness: Float = 1f) {

    /** Квадратична шкала: на малій яскравості (вночі в салоні) повзунок точніший. */
    private val level: Int get() = (255 * brightness * brightness).roundToInt().coerceIn(1, 255)

    fun rgbw(): IntArray {
        if (hue == null) return intArrayOf(0, 0, 0, level)
        val c = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        return intArrayOf(Color.red(c) * level / 255, Color.green(c) * level / 255, Color.blue(c) * level / 255, 0)
    }

    /** Чи контролер зараз світить саме так, як вибрано. */
    fun matches(s: LedProtocol.State): Boolean {
        if (s.on != on) return false
        if (!on) return true
        val want = rgbw()
        val have = intArrayOf(s.r, s.g, s.b, s.w)
        return s.isStatic && want.indices.all { abs(want[it] - have[it]) <= 3 }
    }

    companion object {
        fun from(s: LedProtocol.State): Light {
            val max = maxOf(s.r, s.g, s.b)
            if (max == 0) return Light(s.on, null, sqrt(s.w / 255f).coerceIn(0.05f, 1f))
            val hsv = FloatArray(3)
            Color.RGBToHSV(s.r, s.g, s.b, hsv)
            return Light(s.on, hsv[0], sqrt(max / 255f).coerceIn(0.05f, 1f))
        }
    }
}

/** Збережений вибір: за ним застосунок відновлює підсвітку, якщо контролер збився. */
object Saved {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("led", Context.MODE_PRIVATE)

    fun light(ctx: Context): Light? {
        val p = prefs(ctx)
        if (!p.contains("brightness")) return null
        val hue = p.getFloat("hue", -1f)
        return Light(p.getBoolean("on", true), hue.takeIf { it >= 0f }, p.getFloat("brightness", 1f))
    }

    fun save(ctx: Context, light: Light) {
        prefs(ctx).edit()
            .putBoolean("on", light.on)
            .putFloat("hue", light.hue ?: -1f)
            .putFloat("brightness", light.brightness)
            .apply()
    }

    /** Шаблони користувача: колір + яскравість. Формат: "hue,brightness;…", hue = -1 — білий канал. */
    fun templates(ctx: Context): List<Light> =
        prefs(ctx).getString("templates", "")!!
            .split(';')
            .filter { it.isNotBlank() }
            .mapNotNull { item ->
                val (hue, brightness) = item.split(',').map { it.toFloatOrNull() ?: return@mapNotNull null }
                Light(on = true, hue = hue.takeIf { it >= 0f }, brightness = brightness)
            }

    fun saveTemplates(ctx: Context, list: List<Light>) {
        val text = list.joinToString(";") { "${it.hue ?: -1f},${it.brightness}" }
        prefs(ctx).edit().putString("templates", text).apply()
    }

    /**
     * Чи контролер щойно з'явився після перерви (машину завели), а не просто знову помічений в ефірі.
     * Поки машина працює, Android бачить контролер постійно, і перевіряти щоразу не треба:
     * часті підключення самі збивають дешеві контролери.
     */
    fun appearedAfterGap(ctx: Context, gapMs: Long): Boolean {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val last = p.getLong("lastSeen", 0)
        // Оголошення приходять кожні кілька секунд — на диск пишемо не частіше ніж раз на 10 с.
        if (now - last > 10_000) p.edit().putLong("lastSeen", now).apply()
        return now - last >= gapMs
    }

    /** Поки ми підключені, контролер мовчить в ефірі, тож після з'єднання вважаємо, що бачили його щойно. */
    fun markSeen(ctx: Context) {
        prefs(ctx).edit().putLong("lastSeen", System.currentTimeMillis()).apply()
    }
}
