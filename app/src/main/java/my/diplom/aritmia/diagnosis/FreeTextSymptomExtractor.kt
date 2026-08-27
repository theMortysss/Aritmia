package my.diplom.aritmia.diagnosis

/**
 * Нормализует свободно введённые русские жалобы в устойчивые symptom-concepts.
 * Модель работает по концептам, а не по точным строкам пользователя.
 */
object FreeTextSymptomExtractor {

    data class Extraction(
        /** All complaint concepts known to the patient-facing ontology. */
        val conceptIds: Set<String>,
        /** Only features that are part of the immutable pretrained disease_model v2 input. */
        val modelConceptIds: Set<String>,
        val matchedPhrases: Map<String, List<String>>
    )

    fun extract(texts: List<String>): Extraction {
        val normalizedInputs = texts
            .flatMap { splitComplaint(it) }
            .map(::normalize)
            .filter { it.length >= 3 }
            .distinct()

        val matched = linkedMapOf<String, MutableList<String>>()

        ComplaintOntology.concepts.forEach { concept ->
            val aliases = (concept.aliases + concept.label)
                // A bare "температура" can describe a normal measurement such as 36.6.
                // Require a fever-specific phrase instead of manufacturing a symptom.
                .filterNot { concept.id == "fever" && normalize(it) == "температура" }
                .map(::normalize)
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
            modelConceptIds = ComplaintOntology.toModelConceptIds(matched.keys),
            matchedPhrases = matched.mapValues { (_, values) -> values.distinct() }
        )
    }

    fun vectorize(texts: List<String>): DoubleArray {
        val extraction = extract(texts)
        val index = DiseaseCatalog.concepts.mapIndexed { i, c -> c.id to i }.toMap()
        return DoubleArray(DiseaseCatalog.concepts.size).also { vector ->
            extraction.modelConceptIds.forEach { id -> index[id]?.let { vector[it] = 1.0 } }
        }
    }

    /**
     * Commas normally separate independent complaint fragments, which prevents a word in
     * one clause from completing a symptom alias in another. Two narrow exceptions preserve
     * semantics that Russian users commonly express across punctuation:
     * - breathing context + the immediately following pain clause;
     * - coordinated pain locations such as "боль в груди, спине и животе".
     */
    private fun splitComplaint(raw: String): List<String> {
        val clauses = raw
            .replace(';', '.')
            .replace(',', '.')
            .split('.')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = clauses.toMutableList()
        for (i in 0 until clauses.lastIndex) {
            val context = normalize(clauses[i])
            if (context.contains("вдыхаю") || context.contains("на вдохе") || context.contains("при вдохе")) {
                result += "${clauses[i]} ${clauses[i + 1]}"
            }
        }
        result += expandCoordinatedPain(raw)
        return result.distinct()
    }

    private fun expandCoordinatedPain(raw: String): List<String> {
        val normalized = raw.lowercase().replace('ё', 'е')
        val pattern = Regex("\\bболь\\s+в\\s+([а-я]+)(?:\\s*,\\s*([а-я]+))?\\s+и\\s+([а-я]+)\\b")
        val locationToPhrase = mapOf(
            "груди" to "боль в груди",
            "спине" to "боль в спине",
            "животе" to "боль в животе",
            "руке" to "боль в руке",
            "ноге" to "боль в ноге",
            "шее" to "боль в шее",
            "плече" to "боль в плече",
            "челюсти" to "боль в челюсти",
            "лодыжке" to "боль в лодыжке",
            "ребрах" to "боль в ребрах"
        )

        return pattern.findAll(normalized)
            .filterNot { match -> isExplicitlyNegatedCoordination(normalized, match) }
            .flatMap { match ->
                listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                    .filter { it.isNotBlank() }
                    .mapNotNull { locationToPhrase[it] }
            }
            .toList()
    }

    private fun isExplicitlyNegatedCoordination(normalized: String, match: MatchResult): Boolean {
        val before = normalized.substring(0, match.range.first).trimEnd()
        val after = normalized.substring(match.range.last + 1).trimStart()
        val negatedBefore = Regex("(?:^|\\s)(?:нет|без)(?:\\s+никакой)?$").containsMatchIn(before)
        val negatedAfter = Regex("^(?:нет|отсутствует|не\\s+наблюдается)(?:$|\\s)").containsMatchIn(after)
        return negatedBefore || negatedAfter
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9%+\\s/-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Sparse medical complaints require conservative matching. A partial fuzzy match is
     * dangerous here because a generic word such as "болит" must not turn "болит горло"
     * into a cardiovascular concept.
     *
     * We therefore accept only:
     * 1) an exact/whole-phrase occurrence; or
     * 2) all meaningful words of a multi-word alias, allowing harmless word reordering
     *    and conservative adjective inflection normalization (редкий/редким, частый/частым).
     */
    private fun matches(input: String, alias: String): Boolean {
        if (input == alias) return true
        if ((" $input ").contains(" $alias ")) return true

        val aliasWords = meaningfulWords(alias)
        if (aliasWords.size < 2) return false
        val inputWords = meaningfulWords(input)
        return aliasWords.all { it in inputWords }
    }

    private fun meaningfulWords(value: String): Set<String> = value
        .split(' ')
        .filter { it.length >= 4 }
        .map(::canonicalWord)
        .toSet()

    /**
     * Conservative adjective-ending normalization only. It is deliberately not a general
     * Russian stemmer: single-word aliases still require exact phrase matching, so this
     * cannot turn a generic inflected word into a symptom by itself.
     */
    private fun canonicalWord(word: String): String {
        val endings = listOf(
            "ыми", "ими", "ого", "его", "ому", "ему", "ую", "юю",
            "ый", "ий", "ой", "ая", "яя", "ое", "ее", "ые", "ие",
            "ым", "им", "ом", "ем", "ых", "их"
        )
        val ending = endings.firstOrNull { suffix ->
            word.endsWith(suffix) && word.length - suffix.length >= 4
        }
        return if (ending == null) word else word.dropLast(ending.length)
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

        // Only a small allow-list is treated as direct "не + word" negation. Requiring the
        // negated word to belong to the matched alias keeps positive forms such as
        // "не хватает воздуха" intact and avoids suppressing unrelated nearby symptoms.
        val directlyNegatableAliasWords = setOf(
            "редк", "част", "высок", "низк", "понижен", "пониженн",
            "потерял", "потеряла", "терял", "теряла",
            "трудно", "усиливается"
        )
        val canonicalAliasWords = meaningfulWords(alias)
        for (i in 0 until words.lastIndex) {
            if (words[i] == "не") {
                val next = canonicalWord(words[i + 1])
                if (next in directlyNegatableAliasWords && next in canonicalAliasWords) return true
            }
        }
        return false
    }
}
