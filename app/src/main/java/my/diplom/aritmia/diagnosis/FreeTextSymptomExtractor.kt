package my.diplom.aritmia.diagnosis

/**
 * Нормализует свободно введённые русские жалобы в устойчивые symptom-concepts.
 * Модель работает по концептам, а не по точным строкам пользователя.
 */
object FreeTextSymptomExtractor {

    data class Extraction(
        val conceptIds: Set<String>,
        val matchedPhrases: Map<String, List<String>>
    )

    fun extract(texts: List<String>): Extraction {
        val normalizedInputs = texts
            .flatMap { splitComplaint(it) }
            .map(::normalize)
            .filter { it.length >= 3 }

        val matched = linkedMapOf<String, MutableList<String>>()

        DiseaseCatalog.concepts.forEach { concept ->
            val aliases = (concept.aliases + concept.label).map(::normalize)
            normalizedInputs.forEach { input ->
                aliases.forEach { alias ->
                    if (matches(input, alias) && !isNegated(input, alias)) {
                        matched.getOrPut(concept.id) { mutableListOf() }.add(alias)
                    }
                }
            }
        }

        return Extraction(
            conceptIds = matched.keys,
            matchedPhrases = matched.mapValues { (_, values) -> values.distinct() }
        )
    }

    fun vectorize(texts: List<String>): DoubleArray {
        val extraction = extract(texts)
        val index = DiseaseCatalog.concepts.mapIndexed { i, c -> c.id to i }.toMap()
        return DoubleArray(DiseaseCatalog.concepts.size).also { vector ->
            extraction.conceptIds.forEach { id -> index[id]?.let { vector[it] = 1.0 } }
        }
    }

    private fun splitComplaint(raw: String): List<String> = raw
        .replace(';', '.')
        .replace(',', '.')
        .split('.')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9%+\\s-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun matches(input: String, alias: String): Boolean {
        if (input == alias || input.contains(alias)) return true
        if (alias.length >= 7 && alias.contains(input)) return true

        val aliasWords = alias.split(' ').filter { it.length >= 4 }.toSet()
        if (aliasWords.isEmpty()) return false
        val inputWords = input.split(' ').filter { it.length >= 4 }.toSet()
        val overlap = aliasWords.intersect(inputWords).size.toDouble() / aliasWords.size
        return overlap >= 0.70
    }

    /**
     * Conservative Russian negation handling. We suppress a matched symptom when the
     * complaint explicitly says that it is absent. Phrases like "боль не проходит"
     * must remain positive, so "не" by itself is not treated as a universal negator.
     */
    private fun isNegated(input: String, alias: String): Boolean {
        val escaped = Regex.escape(alias)
        val explicit = listOf(
            Regex("(?:^|\\s)(?:нет|без)\\s+(?:никакой\\s+)?$escaped(?:$|\\s)"),
            Regex("(?:^|\\s)$escaped\\s+(?:нет|отсутствует|не наблюдается)(?:$|\\s)"),
            Regex("(?:^|\\s)(?:отсутствует|не наблюдается)\\s+$escaped(?:$|\\s)")
        )
        if (explicit.any { it.containsMatchIn(input) }) return true

        // Handles natural word order such as "грудь не болит", "голова не кружится".
        val negatedVerbs = setOf(
            "болит", "болят", "кружится", "тошнит", "рвет", "кашляю", "кашляет",
            "задыхаюсь", "потею", "отекает", "отекают", "бьется", "бьётся"
        )
        val words = input.split(' ')
        val aliasWords = alias.split(' ').toSet()
        for (i in 0 until words.lastIndex) {
            if (words[i] == "не" && words[i + 1] in negatedVerbs) {
                val nearby = words.subList((i - 3).coerceAtLeast(0), (i + 4).coerceAtMost(words.size)).toSet()
                if (nearby.intersect(aliasWords).isNotEmpty()) return true
            }
        }
        return false
    }
}
