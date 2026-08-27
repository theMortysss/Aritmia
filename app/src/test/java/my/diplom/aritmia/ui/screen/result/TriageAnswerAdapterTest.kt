package my.diplom.aritmia.ui.screen.result

import my.diplom.aritmia.data.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TriageAnswerAdapterTest {

    @Test
    fun mapsConceptAnswersByPromptId() {
        val mapped = triageAnswersFor(
            symptoms = listOf("низкое давление"),
            rules = emptyList(),
            storedAnswers = mapOf(
                "низкое давление" to listOf("ниже 90/60", "да")
            )
        )

        assertEquals("ниже 90/60", mapped["concept:low_bp:measured_bp"])
        assertEquals("да", mapped["concept:low_bp:symptomatic"])
    }

    @Test
    fun adminRuleAnswerStaysFirstAndConceptAnswersStillMap() {
        val rule = RuleEntity(
            id = 9,
            symptomKey = "низкое давление",
            medicalTerm = "Гипотония",
            probabilityWeight = 0,
            clarifyingQuestions = "Админ-вопрос?",
            answerTriggers = "да=Да;нет=Нет"
        )

        val mapped = triageAnswersFor(
            symptoms = listOf("низкое давление"),
            rules = listOf(rule),
            storedAnswers = mapOf(
                "низкое давление" to listOf("да", "ниже 90/60", "да")
            )
        )

        assertEquals("да", mapped["rule:9:0"])
        assertEquals("ниже 90/60", mapped["concept:low_bp:measured_bp"])
        assertEquals("да", mapped["concept:low_bp:symptomatic"])
    }

    @Test
    fun cannotAnswerIsNotTreatedAsClinicalEvidence() {
        val mapped = triageAnswersFor(
            symptoms = listOf("низкое давление"),
            rules = emptyList(),
            storedAnswers = mapOf(
                "низкое давление" to listOf("не могу ответить", "не могу ответить")
            )
        )

        assertFalse(mapped.containsKey("concept:low_bp:measured_bp"))
        assertFalse(mapped.containsKey("concept:low_bp:symptomatic"))
    }
}
