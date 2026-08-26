package my.diplom.aritmia.diagnosis

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Positive and hard-boundary Russian complaint challenge for the real Android extractor.
 *
 * Positive cases cover every supported cardiovascular class. Boundary cases deliberately
 * overlap cardiovascular concepts but are not safe to resolve from the 47 binary concepts
 * alone. They are research probes, not ground-truth diagnoses.
 */
class CuratedScopeBoundaryExtractionTest {

    private data class Case(
        val group: String,
        val id: String,
        val text: String,
        val expectedConcepts: Set<String>,
        val expectedDiseaseId: String? = null
    )

    private val positiveCases = listOf(
        Case(
            "positive", "atrial_fibrillation", "Пульс нерегулярный, сердце колотится, тяжело дышать, голова кружится",
            setOf("irregular_heartbeat", "palpitations", "dyspnea", "dizziness"), "atrial_fibrillation"
        ),
        Case(
            "positive", "supraventricular_tachycardia", "Частый пульс, сердце колотится, голова кружится, тяжело дышать",
            setOf("fast_heart_rate", "palpitations", "dizziness", "dyspnea"), "supraventricular_tachycardia"
        ),
        Case(
            "positive", "ventricular_tachycardia", "Частый пульс, сердце колотится, потеря сознания, боль в груди, тяжело дышать",
            setOf("fast_heart_rate", "palpitations", "syncope", "chest_pain", "dyspnea"), "ventricular_tachycardia"
        ),
        Case(
            "positive", "sinus_bradycardia", "Редкий пульс, голова кружится, сильная слабость, усталость",
            setOf("slow_heart_rate", "dizziness", "weakness", "fatigue"), "sinus_bradycardia"
        ),
        Case(
            "positive", "heart_block", "Редкий пульс, обмороки, голова кружится, быстро устаю, тяжело дышать",
            setOf("slow_heart_rate", "syncope", "dizziness", "fatigue", "dyspnea"), "heart_block"
        ),
        Case(
            "positive", "stable_angina", "Боль в груди, давление в груди, стеснение в груди, отдает в руку, тяжело дышать",
            setOf("chest_pain", "chest_pressure", "chest_tightness", "arm_pain", "dyspnea"), "stable_angina"
        ),
        Case(
            "positive", "acute_coronary_syndrome", "Давящая боль в груди, холодный пот, тошнота, отдает в левую руку, тяжело дышать",
            setOf("chest_pain", "chest_pressure", "sweating", "nausea", "arm_pain", "dyspnea"), "acute_coronary_syndrome"
        ),
        Case(
            "positive", "heart_failure", "Тяжело дышать, отеки ног, быстро набрал вес, быстро устаю, ночью часто хожу в туалет",
            setOf("dyspnea", "edema", "weight_gain", "fatigue", "nocturia"), "heart_failure"
        ),
        Case(
            "positive", "arterial_hypertension", "Высокое давление, сильно болит голова, голова кружится, сердце колотится",
            setOf("high_bp", "headache", "dizziness", "palpitations"), "arterial_hypertension"
        ),
        Case(
            "positive", "pericarditis", "Боль при вдохе, боль в груди, тяжело дышать, сердце колотится, кашель",
            setOf("pleuritic_pain", "chest_pain", "dyspnea", "palpitations", "cough"), "pericarditis"
        ),
        Case(
            "positive", "cardiomyopathy", "Тяжело дышать, быстро устаю, отеки ног, сердце колотится, боль в груди",
            setOf("dyspnea", "fatigue", "edema", "palpitations", "chest_pain"), "cardiomyopathy"
        ),
        Case(
            "positive", "aortic_valve_disease", "Тяжело дышать, боль в груди, теряю сознание, быстро устаю, сердце колотится",
            setOf("dyspnea", "chest_pain", "syncope", "fatigue", "palpitations"), "aortic_valve_disease"
        ),
        Case(
            "positive", "pulmonary_hypertension", "Тяжело дышать, быстро устаю, голова кружится, теряю сознание, отеки ног, кашель с кровью",
            setOf("dyspnea", "fatigue", "dizziness", "syncope", "edema", "hemoptysis"), "pulmonary_hypertension"
        ),
        Case(
            "positive", "aortic_aneurysm", "Боль в груди, болит спина, болит живот, тяжело дышать",
            setOf("chest_pain", "back_pain", "abdominal_pain", "dyspnea"), "aortic_aneurysm"
        )
    )

    private val boundaryCases = listOf(
        Case(
            "boundary", "panic_like_overlap", "Сердце колотится, тяжело дышать, холодный пот, голова кружится",
            setOf("palpitations", "dyspnea", "sweating", "dizziness")
        ),
        Case(
            "boundary", "reflux_chest_overlap", "Изжога, жжение в груди после еды, тошнота",
            setOf("heartburn", "burning_chest_pain", "nausea")
        ),
        Case(
            "boundary", "chest_wall_overlap", "Боль в груди усиливается при движении, болит плечо, болит спина",
            setOf("chest_pain", "shoulder_pain", "back_pain")
        ),
        Case(
            "boundary", "pleuritic_respiratory_overlap", "Боль при вдохе, кашель с мокротой, тяжело дышать, слышу хрипы при дыхании",
            setOf("pleuritic_pain", "cough", "phlegm", "dyspnea", "abnormal_breathing_sounds")
        ),
        Case(
            "boundary", "anemia_like_overlap", "Быстро устаю, сильная слабость, тяжело дышать, голова кружится",
            setOf("fatigue", "weakness", "dyspnea", "dizziness")
        ),
        Case(
            "boundary", "fluid_retention_overlap", "Отеки ног, быстро набрал вес, ночью часто хожу в туалет, сильная слабость, тяжело дышать",
            setOf("edema", "weight_gain", "nocturia", "weakness", "dyspnea")
        ),
        Case(
            "boundary", "pleuritic_hemoptysis_overlap", "Боль при вдохе, тяжело дышать, кашель с кровью, сердце колотится",
            setOf("pleuritic_pain", "dyspnea", "hemoptysis", "palpitations")
        ),
        Case(
            "boundary", "musculoskeletal_chest_arm_overlap", "Боль в груди, болит рука, болит плечо, болит спина",
            setOf("chest_pain", "arm_pain", "shoulder_pain", "back_pain")
        )
    )

    @Test
    fun exportPositiveAndBoundaryConceptsForScopeResearch() {
        val cases = positiveCases + boundaryCases
        val rows = cases.map { case ->
            val extraction = FreeTextSymptomExtractor.extract(listOf(case.text))
            val missing = case.expectedConcepts - extraction.conceptIds
            assertTrue(
                "Case ${case.id} missed expected concepts $missing; actual=${extraction.conceptIds}",
                missing.isEmpty()
            )
            assertTrue(
                "Case ${case.id} should produce at least two concepts, got ${extraction.conceptIds}",
                extraction.conceptIds.size >= 2
            )
            ExportRow(case, extraction.conceptIds.sorted())
        }

        val output = File("build/curated_scope_boundary_extractions.json")
        output.parentFile?.mkdirs()
        output.writeText(
            rows.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { row -> row.toJson() },
            Charsets.UTF_8
        )

        assertTrue(output.isFile && output.length() > 0L)
        println("Curated scope boundary extractions written to ${output.absolutePath}")
        rows.forEach { row -> println("${row.case.group}/${row.case.id} -> ${row.concepts}") }
    }

    private data class ExportRow(val case: Case, val concepts: List<String>) {
        fun toJson(): String {
            val conceptsJson = concepts.joinToString(", ") { "\"${it.jsonEscape()}\"" }
            val diseaseJson = case.expectedDiseaseId?.let { "\"${it.jsonEscape()}\"" } ?: "null"
            return "  {\"group\": \"${case.group.jsonEscape()}\", \"id\": \"${case.id.jsonEscape()}\", " +
                "\"text\": \"${case.text.jsonEscape()}\", \"expectedDiseaseId\": $diseaseJson, \"conceptIds\": [$conceptsJson]}"
        }
    }

    private companion object {
        fun String.jsonEscape(): String = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
