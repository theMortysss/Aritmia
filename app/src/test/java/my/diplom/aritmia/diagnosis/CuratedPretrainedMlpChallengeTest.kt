package my.diplom.aritmia.diagnosis

import java.io.File
import kotlin.math.abs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end research challenge for the exact pretrained MLP shipped in Android:
 * Russian free text -> real FreeTextSymptomExtractor -> shipped v2 weights -> top-5.
 *
 * Scores are classifier confidence among the 14 supported classes, not clinical
 * probabilities. The curated cases are sanity probes, not clinical validation.
 */
class CuratedPretrainedMlpChallengeTest {

    private data class Case(
        val expectedDiseaseId: String,
        val text: String
    )

    private val cases = listOf(
        Case("atrial_fibrillation", "Пульс нерегулярный, сердце колотится, тяжело дышать, голова кружится"),
        Case("supraventricular_tachycardia", "Частый пульс, сердце колотится, голова кружится, тяжело дышать"),
        Case("ventricular_tachycardia", "Частый пульс, сердце колотится, потеря сознания, боль в груди, тяжело дышать"),
        Case("sinus_bradycardia", "Редкий пульс, голова кружится, сильная слабость, усталость"),
        Case("heart_block", "Редкий пульс, обмороки, голова кружится, быстро устаю, тяжело дышать"),
        Case("stable_angina", "Боль в груди, давление в груди, стеснение в груди, отдает в руку, тяжело дышать"),
        Case("acute_coronary_syndrome", "Давящая боль в груди, холодный пот, тошнота, отдает в левую руку, тяжело дышать"),
        Case("heart_failure", "Тяжело дышать, отеки ног, быстро набрал вес, быстро устаю, ночью часто хожу в туалет"),
        Case("arterial_hypertension", "Высокое давление, сильно болит голова, голова кружится, сердце колотится"),
        Case("pericarditis", "Боль при вдохе, боль в груди, тяжело дышать, сердце колотится, кашель"),
        Case("cardiomyopathy", "Тяжело дышать, быстро устаю, отеки ног, сердце колотится, боль в груди"),
        Case("aortic_valve_disease", "Тяжело дышать, боль в груди, теряю сознание, быстро устаю, сердце колотится"),
        Case("pulmonary_hypertension", "Тяжело дышать, быстро устаю, голова кружится, теряю сознание, отеки ног, кашель с кровью"),
        Case("aortic_aneurysm", "Боль в груди, болит спина, болит живот, тяжело дышать")
    )

    @Test
    fun exportTop5FromExactShippedModel() {
        val snapshot = loadShippedSnapshot()
        assertEquals(DiseaseCatalog.concepts.map { it.id }, snapshot.inputConceptIds)
        assertEquals(DiseaseCatalog.definitions.map { it.id }, snapshot.outputDiseaseIds)

        val network = DiseaseNeuralNetwork(
            inputSize = snapshot.inputConceptIds.size,
            hiddenSize = snapshot.hiddenSize,
            outputSize = snapshot.outputDiseaseIds.size
        )
        network.loadWeights(snapshot)

        val rows = cases.map { case ->
            val extraction = FreeTextSymptomExtractor.extract(listOf(case.text))
            assertTrue(
                "${case.expectedDiseaseId} should have >=2 concepts, got ${extraction.conceptIds}",
                extraction.conceptIds.size >= 2
            )

            val input = FreeTextSymptomExtractor.vectorize(listOf(case.text))
            val probabilities = network.predict(input)
            assertTrue(abs(probabilities.sum() - 1.0) < 1e-9)
            assertTrue(probabilities.all { it in 0.0..1.0 })

            val ranked = probabilities.indices.sortedByDescending { probabilities[it] }
            val expectedIndex = snapshot.outputDiseaseIds.indexOf(case.expectedDiseaseId)
            assertTrue("Unknown expected disease ${case.expectedDiseaseId}", expectedIndex >= 0)
            val expectedRank = ranked.indexOf(expectedIndex) + 1
            val top5 = ranked.take(5).map { index ->
                Prediction(snapshot.outputDiseaseIds[index], probabilities[index])
            }

            Row(
                expectedDiseaseId = case.expectedDiseaseId,
                text = case.text,
                conceptIds = extraction.conceptIds.sorted(),
                expectedRank = expectedRank,
                expectedConfidence = probabilities[expectedIndex],
                top5 = top5
            )
        }

        val top1Hits = rows.count { it.expectedRank == 1 }
        val top5Hits = rows.count { it.expectedRank in 1..5 }
        val output = File("build/pretrained_mlp_curated_report.json")
        output.parentFile?.mkdirs()
        output.writeText(toJson(rows, top1Hits, top5Hits), Charsets.UTF_8)

        assertTrue(output.isFile && output.length() > 0L)
        println("Pretrained MLP curated report written to ${output.absolutePath}")
        println("top1=$top1Hits/${rows.size}; top5=$top5Hits/${rows.size}")
        rows.forEach { row ->
            println(
                "${row.expectedDiseaseId}: rank=${row.expectedRank}, " +
                    "confidence=${"%.6f".format(java.util.Locale.US, row.expectedConfidence)}, " +
                    "top5=${row.top5.map { it.diseaseId }}"
            )
        }
    }

    private fun loadShippedSnapshot(): DiseaseModelSnapshot {
        val modelDir = listOf(
            File("src/main/assets/disease_model"),
            File("app/src/main/assets/disease_model")
        ).firstOrNull { it.isDirectory }
            ?: error("Cannot locate app/src/main/assets/disease_model")

        val parts = modelDir.listFiles { file ->
            file.isFile && file.name.matches(Regex("v2-\\d{2}\\.part"))
        }?.sortedBy { it.name }.orEmpty()

        assertEquals("Expected eight shipped v2 model parts", 8, parts.size)
        val payload = buildString { parts.forEach { append(it.readText(Charsets.UTF_8)) } }
        return Json { ignoreUnknownKeys = true }.decodeFromString(payload)
    }

    private data class Prediction(val diseaseId: String, val confidence: Double)

    private data class Row(
        val expectedDiseaseId: String,
        val text: String,
        val conceptIds: List<String>,
        val expectedRank: Int,
        val expectedConfidence: Double,
        val top5: List<Prediction>
    )

    private fun toJson(rows: List<Row>, top1Hits: Int, top5Hits: Int): String = buildString {
        append("{\n")
        append("  \"notes\": [\n")
        append("    \"Russian complaint text is processed by the real Kotlin FreeTextSymptomExtractor.\",\n")
        append("    \"Predictions use the exact eight v2 model parts shipped in Android assets.\",\n")
        append("    \"Softmax scores are relative classifier confidence among 14 supported classes, not clinical probabilities.\",\n")
        append("    \"This curated challenge is a software/model sanity check, not clinical validation.\"\n")
        append("  ],\n")
        append("  \"caseCount\": ${rows.size},\n")
        append("  \"top1Hits\": $top1Hits,\n")
        append("  \"top5Hits\": $top5Hits,\n")
        append("  \"cases\": [\n")
        rows.forEachIndexed { rowIndex, row ->
            append("    {\n")
            append("      \"expectedDiseaseId\": \"${row.expectedDiseaseId.jsonEscape()}\",\n")
            append("      \"text\": \"${row.text.jsonEscape()}\",\n")
            append("      \"conceptIds\": [")
            append(row.conceptIds.joinToString(", ") { "\"${it.jsonEscape()}\"" })
            append("],\n")
            append("      \"expectedRank\": ${row.expectedRank},\n")
            append("      \"expectedConfidence\": ${row.expectedConfidence},\n")
            append("      \"top5\": [\n")
            row.top5.forEachIndexed { predictionIndex, prediction ->
                append(
                    "        {\"diseaseId\": \"${prediction.diseaseId.jsonEscape()}\", " +
                        "\"confidence\": ${prediction.confidence}}"
                )
                if (predictionIndex != row.top5.lastIndex) append(',')
                append('\n')
            }
            append("      ]\n")
            append("    }")
            if (rowIndex != rows.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    private companion object {
        fun String.jsonEscape(): String = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
