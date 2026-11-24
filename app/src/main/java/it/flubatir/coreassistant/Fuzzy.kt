package it.flubatir.coreassistant

import java.text.Normalizer
import java.util.Locale

/** Confronto "a orecchio" tra quello che il riconoscimento vocale capisce e i nomi reali di app e contatti. */
object Fuzzy {
    private val accents = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
            .replace(accents, "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Chiave fonetica approssimata (italiano/inglese): "flubatir", "fluba tir", "flubatyr" -> stessa chiave. */
    fun sound(s: String): String {
        var t = norm(s).replace(" ", "")
        t = t.replace("ph", "f").replace("ck", "k").replace("ch", "k").replace("qu", "k")
            .replace(Regex("c(?=[aouhlr])"), "k").replace("c", "s").replace("w", "v").replace("y", "i")
            .replace("x", "ks").replace("h", "").replace("z", "s").replace("j", "i")
        return t.replace(Regex("(.)\\1+"), "$1")
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** Punteggio 0..1 tra la richiesta e un candidato. */
    fun score(query: String, cand: String): Double {
        val q = norm(query); val c = norm(cand)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        var best = 0.0
        if (c.startsWith(q) || q.startsWith(c)) best = 0.93
        else if (c.contains(q) || q.contains(c)) best = 0.86
        val qt = q.split(" "); val ct = c.split(" ")
        if (qt.all { t -> ct.any { it == t } }) best = maxOf(best, 0.9)
        else if (qt.any { t -> t.length > 2 && ct.any { it == t } }) best = maxOf(best, 0.78)
        best = maxOf(best, sim(q, c) * 0.97)
        val qs = sound(q); val cs = sound(c)
        if (qs == cs) best = maxOf(best, 0.95)
        best = maxOf(best, sim(qs, cs) * 0.95)
        // confronto token per token (nomi con cognome: "marco" vs "Marco Rossi")
        for (t in qt) for (u in ct) if (t.length > 2 && u.length > 2) best = maxOf(best, sim(sound(t), sound(u)) * 0.85)
        return best
    }

    fun <T> rank(query: String, items: List<T>, names: (T) -> List<String>): List<Pair<T, Double>> =
        items.map { it to (names(it).maxOfOrNull { n -> score(query, n) } ?: 0.0) }
            .filter { it.second > 0.45 }
            .sortedByDescending { it.second }
}
