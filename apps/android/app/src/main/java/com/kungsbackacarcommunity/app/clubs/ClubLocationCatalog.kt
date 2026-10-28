package com.kungsbackacarcommunity.app.clubs

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

internal const val CLUB_LOCATION_ASSET = "clubs/scb-localities-2023.tsv"

internal data class ClubCounty(val code: String, val name: String)

/** A county membership, not a municipality. A locality can have several rows. */
internal data class ClubLocality(
    val code: String,
    val countyCode: String,
    val name: String,
    val municipalities: String,
    // Former locality merged in 2023, e.g. Kungsbacka is now part of Göteborg.
    val partOfCode: String? = null,
)

internal data class ClubLocationSelection(val countyCode: String? = null, val localityCode: String? = null) {
    fun chooseCounty(catalog: ClubLocationCatalog, code: String): ClubLocationSelection = when {
        catalog.county(code) == null -> ClubLocationSelection()
        code == countyCode -> this
        else -> ClubLocationSelection(countyCode = code)
    }

    /** The originating county prevents a stale result from another county being applied. */
    fun chooseLocality(catalog: ClubLocationCatalog, county: String, code: String): ClubLocationSelection =
        if (county == countyCode && catalog.locality(county, code) != null) copy(localityCode = code)
        else copy(localityCode = null)
}

internal class ClubLocationCatalog private constructor(
    val counties: List<ClubCounty>,
    private val places: Map<Pair<String, String>, ClubLocality>,
) {
    private val byCounty = places.values.groupBy { it.countyCode }
    private val searchText = places.mapValues { (_, place) -> clubLocationSearchText("${place.name} ${place.municipalities}") }

    fun county(code: String?) = counties.firstOrNull { it.code == code }
    fun locality(county: String?, code: String?): ClubLocality? {
        if (county == null || code == null) return null
        return places[county to code]
    }
    fun localities(county: String?, query: String = ""): List<ClubLocality> {
        if (county == null) return emptyList()
        val words = clubLocationSearchText(query).split(' ').filter { it.isNotEmpty() }
        return byCounty[county].orEmpty().filter { place ->
            words.all { searchText.getValue(place.countyCode to place.code).contains(it) }
        }
    }

    /** Only verified membership pairs can cross the existing string-only wire boundary. */
    fun wireLocation(selection: ClubLocationSelection): String? {
        val county = county(selection.countyCode) ?: return null
        val place = locality(county.code, selection.localityCode) ?: return null
        return "${place.name}, ${county.name}".takeIf { it.length in 2..80 }
    }

    companion object {
        fun parse(tsv: String): ClubLocationCatalog {
            val lines = tsv.lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.firstOrNull()?.startsWith("# kcc-club-localities-v1;") == true)
            val counties = linkedMapOf<String, ClubCounty>()
            val places = linkedMapOf<Pair<String, String>, ClubLocality>()
            lines.drop(1).forEach { line ->
                val fields = line.split('\t')
                require(fields.all { it.isNotBlank() && it == it.trim() })
                when (fields[0]) {
                    "C" -> {
                        require(fields.size == 3 && fields[1].matches(Regex("[0-9]{2}")))
                        require(counties.put(fields[1], ClubCounty(fields[1], fields[2])) == null)
                    }
                    "L", "A" -> {
                        val alias = fields[0] == "A"
                        require(fields.size == if (alias) 6 else 5)
                        require(counties.containsKey(fields[1]) && fields[2].matches(Regex("[0-9]{4}T[BC][0-9]{3}")))
                        val place = ClubLocality(fields[2], fields[1], fields[3], fields[4], fields.getOrNull(5))
                        require("${place.name}, ${counties.getValue(place.countyCode).name}".length in 2..80)
                        require(places.put(place.countyCode to place.code, place) == null)
                    }
                    else -> error("Unknown club geography row")
                }
            }
            val current = places.values.filter { it.partOfCode == null }
            require(counties.size == 21 && current.size == 2030 && current.map { it.code }.toSet().size == 2017)
            require(places.size - current.size == 32)
            places.values.forEach { place ->
                place.partOfCode?.let { target ->
                    require(places[place.countyCode to target]?.let { it.partOfCode == null } == true)
                }
            }
            require(counties.keys.all { county -> current.any { it.countyCode == county } })
            val collator = Collator.getInstance(Locale.forLanguageTag("sv-SE"))
            val sortedCounties = counties.values.sortedWith { a, b -> collator.compare(a.name, b.name) }
            val sortedPlaces = places.values.sortedWith { a, b ->
                collator.compare(a.name, b.name).takeIf { it != 0 }
                    ?: collator.compare(a.municipalities, b.municipalities).takeIf { it != 0 }
                    ?: a.code.compareTo(b.code)
            }.associateBy { it.countyCode to it.code }
            return ClubLocationCatalog(sortedCounties, sortedPlaces)
        }
    }
}

internal fun clubLocationSearchText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

internal fun clubPresentationFields(name: String, description: String, location: String?, closed: Boolean): Map<String, Any?>? {
    val n = name.trim()
    val d = description.trim()
    val l = location?.trim() ?: return null
    if (n.length !in 3..60 || d.length !in 10..500 || l.length !in 2..80) return null
    return mapOf("name" to n, "description" to d, "location" to l, "visibility" to if (closed) "closed" else "open")
}
