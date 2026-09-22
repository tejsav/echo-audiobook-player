package com.echo.player.update

/** The numbers in a version, ignoring a "v" prefix and anything after a dash: "v1.10-debug" → 1, 10. */
internal fun versionParts(version: String): List<Int> =
    Regex("\\d+").findAll(version.substringBefore('-')).map { it.value.toIntOrNull() ?: 0 }.toList()

/** True when [candidate] is a later version than [current]. "1.3" and "1.3.0" are the same. */
fun isNewer(candidate: String, current: String): Boolean {
    val a = versionParts(candidate)
    val b = versionParts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
