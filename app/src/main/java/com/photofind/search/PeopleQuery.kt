package com.photofind.search

import com.photofind.data.Person
import java.text.Normalizer

/** Lower case without accents, so "zosia" finds "Zośka" and "Zosia", and "lukasz" finds "Łukasz". */
fun foldName(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('ł', 'l')

/**
 * A search split into the named people it mentions and the rest, which is searched by
 * description among the photos showing all of [people]. Each entry is one name; people who
 * share that exact name (not merged yet) are in it together, and a photo of any of them counts.
 */
data class PeopleQuery(val people: List<List<Person>>, val rest: String) {
    val names: List<String> get() = people.map { it.first().name.orEmpty() }
}

/** Words that only join names ("Anna and Piotr", "Anna i Piotr"); they don't describe a photo. */
private val connectors = setOf("and", "i", "oraz", "with", "z", "ze", "&", "+")

/** Polish case endings, without accents, as names decline: Piotr-a, Ewelin-y, Kasi-e, Piotrus-ia. */
private val endings = listOf(
    "a", "y", "i", "e", "u", "o", "ie", "ia", "iu", "ii", "owi", "em", "om", "ach", "ami", "ego", "emu", "ow",
)

private val wordSplit = Regex("[^\\p{L}\\p{N}&+]+")

private class Span(val person: Person, val start: Int, val length: Int, val wholeName: Boolean)

/**
 * Finds named people in [query]. Longer matches win: a full name ("Anna Nowak", "ciocia Kasia")
 * or a run of two or more of its words, then a person whose whole name is one word ("Kasia"
 * over "Kasia Nowak"), then a single first name that only one person has. A match that fits
 * several people equally is left as a description word. Words also match with a Polish case
 * ending ("Anny", "Piotra", "Piotrusia").
 */
fun parsePeopleQuery(query: String, named: List<Person>): PeopleQuery {
    val words = query.split(wordSplit).filter { it.isNotBlank() }
    val folded = words.map(::foldName)
    val names = named.filter { it.name != null }.map { it to foldName(it.name!!).split(wordSplit).filter(String::isNotEmpty) }

    // Every place where a run of a name's words appears in the query.
    val spans = ArrayList<Span>()
    for ((person, parts) in names) {
        for (from in parts.indices) {
            for (to in from + 1..parts.size) {
                val run = parts.subList(from, to)
                val whole = from == 0 && to == parts.size
                // A single word counts only as a whole one-word name or as a first name.
                if (run.size == 1 && !whole && from != 0) continue
                for (start in 0..words.size - run.size) {
                    if (run.indices.all { sameName(folded[start + it], run[it]) }) {
                        spans += Span(person, start, run.size, whole)
                    }
                }
            }
        }
    }

    val used = BooleanArray(words.size)
    val found = LinkedHashMap<Long, List<Person>>()
    val ordered = spans.groupBy { it.start to it.length }.entries
        .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, List<Span>>> { it.key.second }
            .thenByDescending { entry -> entry.value.any { it.wholeName } })
    for ((place, candidates) in ordered) {
        val (start, length) = place
        if ((start until start + length).any { used[it] }) continue
        val open = candidates.filter { it.person.id !in found }.distinctBy { it.person.id }
        val whole = open.filter { it.wholeName }
        val pick = when {
            whole.isNotEmpty() && whole.map { foldName(it.person.name!!) }.distinct().size == 1 -> whole
            whole.isEmpty() && open.size == 1 -> open
            else -> null
        } ?: continue
        (start until start + length).forEach { used[it] = true }
        found[pick.first().person.id] = pick.map { it.person }
    }

    if (found.isEmpty()) return PeopleQuery(emptyList(), query.trim())
    val rest = words.indices.filter { !used[it] && folded[it] !in connectors }.joinToString(" ") { words[it] }
    return PeopleQuery(found.values.toList(), rest)
}

/** Equal, or the name with a Polish case ending, on the whole name or on all but its last letter. */
private fun sameName(word: String, name: String): Boolean {
    if (word == name) return true
    if (word.length < 3 || !word.startsWith(name.take(maxOf(name.length - 1, 1)))) return false
    if (endings.any { word == name + it }) return true
    // Names in -ia lose the last letter: Ania → Ani, Kasia → Kasi.
    if (name.endsWith("ia") && word == name.dropLast(1)) return true
    return name.length >= 4 && endings.any { word == name.dropLast(1) + it }
}
