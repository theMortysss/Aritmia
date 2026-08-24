package my.diplom.aritmia.diagnosis

/**
 * Нормализует свободно введённые русские жалобы в устойчивые симптом-концепты.
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
                    if (matches(input, alias)) {
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

        // Небольшая устойчивость к свободной формулировке:
        // считаем совпадением, если совпало >= 70% значимых слов alias.
        val aliasWords = alias.split(' ').filter { it.length >= 4 }.toSet()
        if (aliasWords.isEmpty()) return false
        val inputWords = input.split(' ').filter { it.length >= 4 }.toSet()
        val overlap = aliasWords.intersect(inputWords).size.toDouble() / aliasWords.size
        return overlap >= 0.70
    }
}
