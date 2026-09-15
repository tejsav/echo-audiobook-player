package com.echo.player.util

private val SEPARATORS = charArrayOf(' ', '_', '-', '.', '·')
private val WHITESPACE = Regex("\\s+")

/**
 * The part of the name every chapter shares, cut back to its last separator so a word or a number
 * is never split. "OSHO-Maha_Geeta_01" to "OSHO-Maha_Geeta_82" gives "OSHO-Maha_Geeta_". Empty when
 * nothing is shared.
 */
fun sharedPrefix(titles: List<String>): String {
    if (titles.size < 2) return ""
    var prefix = titles.first()
    for (title in titles.drop(1)) {
        var i = 0
        while (i < prefix.length && i < title.length && prefix[i] == title[i]) i++
        prefix = prefix.substring(0, i)
        if (prefix.isEmpty()) return ""
    }
    val cut = prefix.lastIndexOfAny(SEPARATORS)
    return if (cut < 0) "" else prefix.substring(0, cut + 1)
}

/**
 * How a chapter reads with tidying on. A bare number ("20") is shown with the series name, because
 * a number alone says nothing. If tidying would leave nothing, the original name is kept.
 */
fun tidyChapterTitle(raw: String, prefix: String, seriesTitle: String): String {
    val rest = (if (prefix.isNotEmpty() && raw.startsWith(prefix)) raw.substring(prefix.length) else raw)
        .replace('_', ' ')
        .replace(WHITESPACE, " ")
        .trim()
        .trimStart(*SEPARATORS)
        .trim()
    if (rest.isEmpty()) return raw.replace('_', ' ').trim()
    return if (rest.all { it.isDigit() }) "$seriesTitle · $rest" else rest
}
