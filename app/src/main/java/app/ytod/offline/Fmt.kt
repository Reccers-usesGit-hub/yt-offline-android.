package app.ytod.offline

import java.util.Locale

object Fmt {
    fun compact(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> one(n / 1e3) + "K"
        n < 1_000_000_000 -> one(n / 1e6) + "M"
        else -> one(n / 1e9) + "B"
    }

    private fun one(d: Double): String {
        val s = if (d < 10) String.format(Locale.US, "%.1f", Math.floor(d * 10) / 10) else d.toLong().toString()
        return s.removeSuffix(".0")
    }

    fun views(n: Long): String = when {
        n < 0 -> ""
        n == 1L -> "1 view"
        else -> compact(n) + " views"
    }

    fun ago(ms: Long): String {
        if (ms <= 0) return ""
        val s = (System.currentTimeMillis() - ms) / 1000
        val units = listOf("year" to 31_536_000L, "month" to 2_592_000L, "week" to 604_800L,
            "day" to 86_400L, "hour" to 3_600L, "minute" to 60L)
        for ((name, sec) in units) {
            val v = s / sec
            if (v >= 1) return "$v $name${if (v > 1) "s" else ""} ago"
        }
        return "just now"
    }

    fun time(totalSeconds: Long): String {
        val t = totalSeconds.coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", b / 1e9)
        b >= 1_000_000 -> "${(b / 1e6).toLong()} MB"
        else -> "${(b / 1e3).toLong().coerceAtLeast(1)} KB"
    }
}
