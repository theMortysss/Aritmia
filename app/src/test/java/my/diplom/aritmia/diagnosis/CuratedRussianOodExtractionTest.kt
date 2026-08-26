package my.diplom.aritmia.diagnosis

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Curated Russian free-text OOD challenge for the real Android complaint extractor.
 *
 * These examples are deliberately non-cardiovascular or plausibly non-cardiovascular
 * complaints that overlap the 47 concepts used by the closed-set disease model. The
 * test exports the concepts produced by the actual Kotlin extractor so the research
 * scope-detector evaluation does not rely on a separate NLP implementation.
 */
class CuratedRussianOodExtractionTest {

    private data class Case(val id: String, val text: String)

    private val cases = listOf(
        Case("gastroenteritis_like", "Болит живот, тошнит, была рвота"),
        Case("food_poisoning_like", "Меня рвет, тошнит, болит живот, чувствую слабость"),
        Case("migraine_like", "Сильно болит голова, тошнит, кружится голова"),
        Case("bronchitis_like", "Кашель с мокротой, слышу хрипы при дыхании, быстро устаю"),
        Case("asthma_like", "Свист при дыхании, тяжело дышать, кашляю"),
        Case("pneumonia_like", "Кашель с мокротой, тяжело дышать, чувствую слабость, быстро дышу"),
        Case("neck_shoulder_back_pain", "Болит шея, болит плечо, болит спина"),
        Case("leg_musculoskeletal", "Болят ноги, сводит ноги, больно ходить"),
        Case("rib_back_shoulder_pain", "Болят ребра, болит спина, болит плечо"),
        Case("insomnia_headache_fatigue", "Плохо сплю, болит голова, чувствую усталость"),
        Case("reflux_like", "Изжога, жжение в животе, тошнота"),
        Case("viral_syndrome_like", "Кашель, сильно болит голова, усталость, слабость"),
        Case("limb_weakness_musculoskeletal", "Слабость в руках, слабость в ногах, болит шея"),
        Case("renal_like_adversarial", "Ночью часто хожу в туалет, отеки ног, чувствую слабость"),
        Case("respiratory_adversarial", "Кашляю, мокрота, тяжело дышать, хрипы")
    )

    @Test
    fun exportActualExtractorConceptsForResearchScopeChallenge() {
        val rows = cases.map { case ->
            val extraction = FreeTextSymptomExtractor.extract(listOf(case.text))
            assertTrue(
                "Curated challenge case ${case.id} should produce at least two concepts, got ${extraction.conceptIds}",
                extraction.conceptIds.size >= 2
            )
            Triple(case.id, case.text, extraction.conceptIds.sorted())
        }

        val output = File("build/curated_russian_ood_extractions.json")
        output.parentFile.mkdirs()
        output.writeText(
            rows.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { (id, text, concepts) ->
                val conceptsJson = concepts.joinToString(", ") { "\"${it.jsonEscape()}\"" }
                "  {\"id\": \"${id.jsonEscape()}\", \"text\": \"${text.jsonEscape()}\", \"conceptIds\": [$conceptsJson]}"
            },
            Charsets.UTF_8
        )

        assertTrue(output.isFile && output.length() > 0L)
        println("Curated OOD extractions written to ${output.absolutePath}")
        rows.forEach { (id, _, concepts) -> println("$id -> $concepts") }
    }

    private fun String.jsonEscape(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
