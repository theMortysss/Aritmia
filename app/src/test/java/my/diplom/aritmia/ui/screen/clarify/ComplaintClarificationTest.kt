package my.diplom.aritmia.ui.screen.clarify

import my.diplom.aritmia.data.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplaintClarificationTest {

    @Test
    fun lowBloodPressureAliasesShareConceptQuestions() {
        val direct = clarificationPromptsFor("низкое давление", emptyList())
        val synonym = clarificationPromptsFor("давление упало", emptyList())

        assertTrue(direct.isNotEmpty())
        assertEquals(direct.map { it.id }, synonym.map { it.id })
        assertTrue(direct.first().options.contains("ниже 90/60"))
        assertTrue(direct.first().options.contains("не могу ответить"))
    }

    @Test
    fun adminRuleQuestionsOverrideOntologyQuestions() {
        val rule = RuleEntity(
            id = 7,
            symptomKey = "низкое давление",
            medicalTerm = "Гипотония",
            probabilityWeight = 0,
            clarifyingQuestions = "Пользовательский вопрос?",
            answerTriggers = "да=Ответ да;нет=Ответ нет"
        )

        val prompts = clarificationPromptsFor("низкое давление", listOf(rule))

        assertEquals(1, prompts.size)
        assertEquals("Пользовательский вопрос?", prompts.single().text)
        assertTrue(prompts.single().options.containsAll(listOf("да", "нет", "не могу ответить")))
    }
}
