package com.photofind.search

import com.photofind.data.Person
import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleQueryTest {

    private var nextId = 1L
    private fun person(name: String) = Person(nextId++, name, 10, 0)

    private val piotr = person("Piotr")
    private val piotrSenior = person("Piotr Senior")
    private val piotrus = person("Piotruś")
    private val ania = person("Ania")
    private val ewelina = person("Ewelina")
    private val kasia = person("Kasia")
    private val kasiaS = person("Kasia Stachniak (PMI)")
    private val ciociaKasia = person("ciocia Kasia")
    private val konrad = person("Konrad (sąsiad)")
    private val kuba = person("Kuba")
    private val martaZ = person("Marta Zygmunciak")
    private val martaK = person("Marta Kędziela")
    private val jola1 = person("ciocia Jola")
    private val jola2 = person("ciocia Jola")
    private val babcia = person("(pra)Babcia Marysia")
    private val lukasz = person("Łukasz Rogala")
    private val everyone = listOf(
        piotr, piotrSenior, piotrus, ania, ewelina, kasia, kasiaS, ciociaKasia, konrad, kuba,
        martaZ, martaK, jola1, jola2, babcia, lukasz,
    )

    private fun parse(query: String) = parsePeopleQuery(query, everyone)

    private fun assertFinds(query: String, rest: String, vararg people: List<Person>) {
        val parsed = parse(query)
        assertEquals(query, people.map { group -> group.map { it.id }.toSet() }, parsed.people.map { group -> group.map { it.id }.toSet() })
        assertEquals(query, rest, parsed.rest)
    }

    @Test fun exactOneWordNameWinsOverFirstNames() = assertFinds("Piotr", "", listOf(piotr))

    @Test fun fullNameWinsOverItsFirstName() = assertFinds("Piotr Senior", "", listOf(piotrSenior))

    @Test fun twoPeopleAndADescription() =
        assertFinds("Piotruś i Ania na plaży", "na plaży", listOf(piotrus), listOf(ania))

    @Test fun declinedNames() {
        assertFinds("zdjęcia Piotrusia", "zdjęcia", listOf(piotrus))
        assertFinds("Piotra", "", listOf(piotr))
        assertFinds("Ani", "", listOf(ania))
        assertFinds("z Eweliną", "", listOf(ewelina))
    }

    @Test fun accentsAndCaseIgnored() = assertFinds("lukasz rogala", "", listOf(lukasz))

    @Test fun bracketsAreNotPartOfWords() = assertFinds("Konrad rower", "rower", listOf(konrad))

    @Test fun nameSharedByUnmergedPeopleMeansEither() = assertFinds("ciocia Jola", "", listOf(jola1, jola2))

    @Test fun twoWordsOfALongerName() = assertFinds("babcia Marysia w ogrodzie", "w ogrodzie", listOf(babcia))

    @Test fun ambiguousFirstNameStaysADescription() = assertFinds("Marta", "Marta")

    @Test fun oneWordNameBeatsLongerNamesStartingWithIt() = assertFinds("Kasia", "", listOf(kasia))

    @Test fun ordinaryWordsThatLookLikeNamesDontMatch() {
        assertFinds("kubek kawy", "kubek kawy")
        assertFinds("Kuba z kubkiem", "kubkiem", listOf(kuba))
    }

    @Test fun noNamesLeavesQueryAlone() = assertFinds("zachód słońca nad morzem", "zachód słońca nad morzem")
}
