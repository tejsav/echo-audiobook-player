package com.echo.player.data

/**
 * Pairs the chapters in a backup with the chapters of the same folder on this device, where every
 * file has a new address. When the folder looks the same — same number of files, and most names in
 * the same places — chapters are paired by position. Otherwise by name. A chapter with no match is
 * left out rather than attached to the wrong file.
 */
fun matchChapters(oldTitles: List<String>, newTitles: List<String>): Map<Int, Int> {
    if (oldTitles.size == newTitles.size && oldTitles.isNotEmpty()) {
        val samePlace = oldTitles.indices.count { oldTitles[it] == newTitles[it] }
        if (samePlace * 2 >= oldTitles.size) return oldTitles.indices.associateWith { it }
    }
    val byTitle = HashMap<String, Int>()
    newTitles.forEachIndexed { index, title -> byTitle.putIfAbsent(title, index) }
    return oldTitles.withIndex()
        .mapNotNull { (index, title) -> byTitle[title]?.let { index to it } }
        .toMap()
}
