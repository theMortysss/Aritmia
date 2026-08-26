package my.diplom.aritmia.diagnosis

import java.io.File
import kotlin.math.abs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen evaluation-only robustness challenge for the shipped cardiovascular model.
 * Never use these texts for training or augmentation of a future model version.
 */
class FrozenRussianRobustnessChallengeTest {

    private data class Case(val diseaseId: String, val variant: String, val text: String)
    private data class Prediction(val diseaseId: String, val confidence: Double)
    private data class Row(
        val case: Case,
        val concepts: List<String>,
        val gate: String,
        val rank: Int?,
        val confidence: Double?,
        val top5: List<Prediction>
    )

    private val cases: List<Case> = CASES.trimIndent().lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val parts = line.split('|', limit = 3)
            require(parts.size == 3) { "Bad frozen case: $line" }
            Case(parts[0], parts[1], parts[2])
        }
        .toList()

    @Test
    fun exportFrozenRobustnessReport() {
        assertEquals(84, cases.size)
        assertEquals(DiseaseCatalog.definitions.map { it.id }.toSet(), cases.map { it.diseaseId }.toSet())
        cases.groupBy { it.diseaseId }.forEach { (id, values) ->
            assertEquals("$id must have six frozen variants", 6, values.size)
        }

        val snapshot = loadSnapshot()
        assertEquals(DiseaseCatalog.concepts.map { it.id }, snapshot.inputConceptIds)
        assertEquals(DiseaseCatalog.definitions.map { it.id }, snapshot.outputDiseaseIds)
        val network = DiseaseNeuralNetwork(snapshot.inputConceptIds.size, snapshot.hiddenSize, snapshot.outputDiseaseIds.size)
        network.loadWeights(snapshot)

        val rows = cases.map { case ->
            val concepts = FreeTextSymptomExtractor.extract(listOf(case.text)).conceptIds.sorted()
            val gate = when (concepts.size) {
                0 -> "OUT_OF_SCOPE"
                1 -> "INSUFFICIENT_EVIDENCE"
                else -> "RANKED"
            }
            if (gate != "RANKED") {
                Row(case, concepts, gate, null, null, emptyList())
            } else {
                val probs = network.predict(FreeTextSymptomExtractor.vectorize(listOf(case.text)))
                assertTrue(abs(probs.sum() - 1.0) < 1e-9)
                val ranked = probs.indices.sortedByDescending { probs[it] }
                val expected = snapshot.outputDiseaseIds.indexOf(case.diseaseId)
                assertTrue(expected >= 0)
                Row(
                    case,
                    concepts,
                    gate,
                    ranked.indexOf(expected) + 1,
                    probs[expected],
                    ranked.take(5).map { Prediction(snapshot.outputDiseaseIds[it], probs[it]) }
                )
            }
        }

        val rankedCount = rows.count { it.gate == "RANKED" }
        val top1 = rows.count { it.rank == 1 }
        val top5 = rows.count { it.rank != null && it.rank in 1..5 }
        val output = File("build/frozen_russian_robustness_report.json")
        output.parentFile?.mkdirs()
        output.writeText(toJson(rows, rankedCount, top1, top5), Charsets.UTF_8)
        assertTrue(output.isFile && output.length() > 0L)

        println("FROZEN_ROBUSTNESS cases=${rows.size} ranked=$rankedCount top1=$top1 top5=$top5")
        DiseaseCatalog.definitions.forEach { definition ->
            val group = rows.filter { it.case.diseaseId == definition.id }
            println(
                "CLASS ${definition.id}: ranked=${group.count { it.gate == \"RANKED\" }}/6 " +
                    "top1=${group.count { it.rank == 1 }}/6 top5=${group.count { it.rank != null && it.rank in 1..5 }}/6 " +
                    "concepts=${group.map { it.concepts.size }}"
            )
        }
        rows.filter { it.gate != "RANKED" || it.rank != 1 }.forEach { row ->
            println(
                "FAILURE ${row.case.diseaseId}/${row.case.variant}: gate=${row.gate} concepts=${row.concepts} " +
                    "rank=${row.rank} top5=${row.top5.map { it.diseaseId }} text=${row.case.text}"
            )
        }
    }

    private fun loadSnapshot(): DiseaseModelSnapshot {
        val dir = listOf(File("src/main/assets/disease_model"), File("app/src/main/assets/disease_model"))
            .firstOrNull { it.isDirectory } ?: error("Cannot locate shipped disease model")
        val parts = dir.listFiles { file -> file.isFile && file.name.matches(Regex("v2-\\d{2}\\.part")) }
            ?.sortedBy { it.name }.orEmpty()
        assertEquals(8, parts.size)
        return Json { ignoreUnknownKeys = true }.decodeFromString(
            buildString { parts.forEach { append(it.readText(Charsets.UTF_8)) } }
        )
    }

    private fun toJson(rows: List<Row>, ranked: Int, top1: Int, top5: Int): String = buildString {
        append("{\n  \"frozen\": true,\n  \"trainingUseForbidden\": true,\n")
        append("  \"caseCount\": ${rows.size},\n  \"rankedCount\": $ranked,\n")
        append("  \"gateRejectedCount\": ${rows.size - ranked},\n  \"top1Hits\": $top1,\n  \"top5Hits\": $top5,\n")
        append("  \"cases\": [\n")
        rows.forEachIndexed { index, row ->
            append("    {\"diseaseId\": \"${row.case.diseaseId}\", \"variant\": \"${row.case.variant}\", ")
            append("\"text\": \"${row.case.text.escape()}\", \"concepts\": [")
            append(row.concepts.joinToString(",") { "\"${it.escape()}\"" })
            append("], \"gate\": \"${row.gate}\", \"rank\": ${row.rank ?: "null"}, ")
            append("\"confidence\": ${row.confidence ?: "null"}, \"top5\": [")
            append(row.top5.joinToString(",") { "{\"diseaseId\":\"${it.diseaseId}\",\"confidence\":${it.confidence}}" })
            append("]}")
            if (index != rows.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n}\n")
    }

    private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private companion object {
        val CASES = """
atrial_fibrillation|core|Пульс нерегулярный, сердце колотится, тяжело дышать, голова кружится
atrial_fibrillation|conversational|Сердце то сбивается, то сильно стучит, иногда не хватает воздуха и кружится голова
atrial_fibrillation|reordered|Голова кружится и тяжело дышать, при этом чувствую сердцебиение и нерегулярный пульс
atrial_fibrillation|short|Пульс нерегулярный, сердце колотится
atrial_fibrillation|noise|Сердце бьется неровно, чувствую сердцебиение и одышку, еще немного болит горло
atrial_fibrillation|negation|Пульс нерегулярный и сердце колотится, но боль в груди отсутствует
supraventricular_tachycardia|core|Частый пульс, сердце колотится, голова кружится, тяжело дышать
supraventricular_tachycardia|conversational|Пульс внезапно становится очень частым, сердце сильно бьется, появляется головокружение и одышка
supraventricular_tachycardia|reordered|Тяжело дышать и кружится голова, а сердце колотится и пульс быстрый
supraventricular_tachycardia|short|Частый пульс, сердце колотится
supraventricular_tachycardia|noise|Учащенный пульс и сердцебиение, иногда не хватает воздуха, плюс заложен нос
supraventricular_tachycardia|negation|Пульс быстрый и сердце сильно бьется, но обмороков нет
ventricular_tachycardia|core|Частый пульс, сердце колотится, потеря сознания, боль в груди, тяжело дышать
ventricular_tachycardia|conversational|Сердце бьется очень быстро, кружится голова, однажды потерял сознание, бывает боль в груди и одышка
ventricular_tachycardia|reordered|После боли в груди и нехватки воздуха потерял сознание, пульс при этом был очень частым
ventricular_tachycardia|short|Частый пульс и потеря сознания
ventricular_tachycardia|noise|Пульс ускорился, сердце колотится, был обморок и боль в груди, еще першит горло
ventricular_tachycardia|negation|Частый пульс, обморок и боль в груди, но кашля нет
sinus_bradycardia|core|Редкий пульс, голова кружится, сильная слабость, усталость
sinus_bradycardia|conversational|Пульс стал очень редким, часто кружится голова, нет сил и быстро устаю
sinus_bradycardia|reordered|Сильная слабость и усталость, голова кружится, пульс при этом редкий
sinus_bradycardia|short|Редкий пульс и сильная слабость
sinus_bradycardia|noise|Низкий пульс, головокружение и слабость, еще немного насморк
sinus_bradycardia|negation|Редкий пульс, голова кружится и нет сил, но боль в груди отсутствует
heart_block|core|Редкий пульс, обмороки, голова кружится, быстро устаю, тяжело дышать
heart_block|conversational|Пульс замедлился, несколько раз падал в обморок, кружится голова, быстро устаю и не хватает воздуха
heart_block|reordered|Тяжело дышать и быстро устаю, были обмороки и головокружение, пульс редкий
heart_block|short|Редкий пульс и обмороки
heart_block|noise|Пульс редкий, теряю сознание и кружится голова, еще болит горло
heart_block|negation|Редкий пульс и обмороки, но боли в груди нет
stable_angina|core|Боль в груди, давление в груди, стеснение в груди, отдает в руку, тяжело дышать
stable_angina|conversational|Грудь сжимает и давит, боль отдает в левую руку, становится тяжело дышать
stable_angina|reordered|Тяжело дышать, в руку отдает боль, а в груди чувствую давление и стеснение
stable_angina|short|Боль в груди и давление в груди
stable_angina|noise|Боль за грудиной, грудь давит и отдает в руку, еще слегка болит колено
stable_angina|negation|Давление и боль в груди отдают в руку, но тошноты нет
acute_coronary_syndrome|core|Давящая боль в груди, холодный пот, тошнота, отдает в левую руку, тяжело дышать
acute_coronary_syndrome|conversational|Сильно давит и болит в груди, бросает в холодный пот, тошнит, боль идет в левую руку и не хватает воздуха
acute_coronary_syndrome|reordered|Тошнит и выступил холодный пот, тяжело дышать, боль в груди давящая и отдает в руку
acute_coronary_syndrome|short|Давящая боль в груди и холодный пот
acute_coronary_syndrome|noise|Давит в груди, тошнит, холодный пот и боль отдает в руку, еще насморк
acute_coronary_syndrome|negation|Давящая боль в груди, холодный пот и тошнота, но кашля нет
heart_failure|core|Тяжело дышать, отеки ног, быстро набрал вес, быстро устаю, ночью часто хожу в туалет
heart_failure|conversational|Не хватает воздуха, ноги отекают, вес быстро увеличился, постоянно устаю и ночью часто хожу в туалет
heart_failure|reordered|Ночью часто хожу в туалет и быстро устаю, ноги отекают, набрал вес и появилась одышка
heart_failure|short|Одышка и отеки ног
heart_failure|noise|Тяжело дышать, отеки ног и резкий набор веса, еще болит горло
heart_failure|negation|Одышка, отеки ног и быстро устаю, но боль в груди отсутствует
arterial_hypertension|core|Высокое давление, сильно болит голова, голова кружится, сердце колотится
arterial_hypertension|conversational|Давление высокое, сильно болит голова, периодически кружится голова и чувствую сердцебиение
arterial_hypertension|reordered|Сердце колотится и кружится голова, сильно болит голова, давление при этом повышенное
arterial_hypertension|short|Высокое давление и болит голова
arterial_hypertension|noise|Повышенное давление, головная боль и головокружение, еще заложен нос
arterial_hypertension|negation|Высокое давление, болит голова и сердце колотится, но боли в груди нет
pericarditis|core|Боль при вдохе, боль в груди, тяжело дышать, сердце колотится, кашель
pericarditis|conversational|Когда вдыхаю, боль в груди усиливается, трудно дышать, есть кашель и чувствую сердцебиение
pericarditis|reordered|Кашель и сердцебиение, тяжело дышать, а при вдохе появляется боль в груди
pericarditis|short|Боль при вдохе и боль в груди
pericarditis|noise|Больно дышать, есть боль в груди и кашель, еще першит горло
pericarditis|negation|Боль при вдохе и в груди, тяжело дышать, но тошноты нет
cardiomyopathy|core|Тяжело дышать, быстро устаю, отеки ног, сердце колотится, боль в груди
cardiomyopathy|conversational|Не хватает воздуха, очень быстро устаю, ноги отекают, чувствую сердцебиение и иногда болит грудь
cardiomyopathy|reordered|Боль в груди и сердцебиение, ноги отекают, быстро устаю и появилась одышка
cardiomyopathy|short|Одышка и отеки ног
cardiomyopathy|noise|Тяжело дышать, быстро устаю, ноги отекают и сердце стучит, еще болит горло
cardiomyopathy|negation|Одышка, усталость, отеки ног и сердцебиение, но обмороков нет
aortic_valve_disease|core|Тяжело дышать, боль в груди, теряю сознание, быстро устаю, сердце колотится
aortic_valve_disease|conversational|Появляется одышка и боль в груди, несколько раз терял сознание, быстро устаю и чувствую сердцебиение
aortic_valve_disease|reordered|Сердце колотится и быстро устаю, бывают обмороки, боль в груди и нехватка воздуха
aortic_valve_disease|short|Боль в груди и теряю сознание
aortic_valve_disease|noise|Одышка, боль в груди, обмороки и усталость, еще заложен нос
aortic_valve_disease|negation|Боль в груди, одышка и обмороки, но кашля нет
pulmonary_hypertension|core|Тяжело дышать, быстро устаю, голова кружится, теряю сознание, отеки ног, кашель с кровью
pulmonary_hypertension|conversational|Не хватает воздуха, быстро устаю, кружится голова, бывают обмороки, ноги отекают и при кашле бывает кровь
pulmonary_hypertension|reordered|Кашель с кровью и отеки ног, были обмороки и головокружение, постоянно устаю и тяжело дышать
pulmonary_hypertension|short|Одышка и кашель с кровью
pulmonary_hypertension|noise|Тяжело дышать, отеки ног и кашель с кровью, еще насморк
pulmonary_hypertension|negation|Одышка, отеки ног, головокружение и кашель с кровью, но боли в груди нет
aortic_aneurysm|core|Боль в груди, болит спина, болит живот, тяжело дышать
aortic_aneurysm|conversational|Сильно болит грудь, одновременно болит спина и живот, стало тяжело дышать
aortic_aneurysm|reordered|Тяжело дышать, болит живот и спина, есть боль в груди
aortic_aneurysm|short|Боль в груди и болит спина
aortic_aneurysm|noise|Боль в груди, спине и животе, появилась одышка, еще болит горло
aortic_aneurysm|negation|Боль в груди, спине и животе, тяжело дышать, но тошноты нет
        """
    }
}
