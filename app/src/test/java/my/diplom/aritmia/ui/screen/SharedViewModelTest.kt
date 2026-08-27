package my.diplom.aritmia.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedViewModelTest {

    @Test
    fun followUpKeepsAnswersForUnchangedComplaintsAndDropsRemovedOnes() {
        val viewModel = SharedViewModel()
        viewModel.setData(
            symptoms = listOf("тяжело дышать", "слабость"),
            userId = 42,
            newAnswers = mapOf(
                "тяжело дышать" to mutableListOf("да"),
                "слабость" to mutableListOf("нет")
            )
        )

        viewModel.updateComplaintsPreservingAnswers(
            symptoms = listOf("тяжело дышать", "сердце колотится"),
            userId = 42
        )

        assertEquals(listOf("да"), viewModel.answers.value["тяжело дышать"])
        assertTrue("слабость" !in viewModel.answers.value)
        assertEquals(
            listOf("тяжело дышать", "сердце колотится"),
            viewModel.symptoms.value
        )
    }

    @Test
    fun startingFreshAssessmentStillClearsOldAnswers() {
        val viewModel = SharedViewModel()
        viewModel.setData(
            symptoms = listOf("тяжело дышать"),
            userId = 42,
            newAnswers = mapOf("тяжело дышать" to mutableListOf("да"))
        )

        viewModel.setData(symptoms = listOf("тяжело дышать"), userId = 42)

        assertTrue(viewModel.answers.value.isEmpty())
    }
}
