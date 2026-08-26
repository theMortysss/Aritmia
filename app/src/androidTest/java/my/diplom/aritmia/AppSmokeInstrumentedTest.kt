package my.diplom.aritmia

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.diplom.aritmia.diagnosis.DiseaseAssessmentStatus
import my.diplom.aritmia.diagnosis.DiseaseNetworkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeInstrumentedTest {

    @Test
    fun mainActivityLaunchesWithRealHiltApplication() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
            }
        }
    }

    @Test
    fun irrelevantComplaintIsOutOfScope() = runBlocking {
        val repository = repository()
        val assessment = repository.assess(listOf("болит горло"))

        assertEquals(DiseaseAssessmentStatus.OUT_OF_SCOPE, assessment.status)
        assertTrue(assessment.recognizedConceptIds.isEmpty())
        assertTrue(assessment.candidates.isEmpty())
    }

    @Test
    fun oneToThreeCardiovascularConceptsAbstainFromRanking() = runBlocking {
        val repository = repository()

        val one = repository.assess(listOf("болит грудь"))
        assertEquals(DiseaseAssessmentStatus.INSUFFICIENT_EVIDENCE, one.status)
        assertEquals(1, one.recognizedConceptIds.size)
        assertTrue(one.candidates.isEmpty())

        val two = repository.assess(
            listOf(
                "пульс нерегулярный",
                "чувствую сердцебиение"
            )
        )
        assertEquals(DiseaseAssessmentStatus.INSUFFICIENT_EVIDENCE, two.status)
        assertEquals(2, two.recognizedConceptIds.size)
        assertTrue(two.candidates.isEmpty())

        val three = repository.assess(
            listOf(
                "пульс нерегулярный",
                "чувствую сердцебиение",
                "одышка"
            )
        )
        assertEquals(DiseaseAssessmentStatus.INSUFFICIENT_EVIDENCE, three.status)
        assertEquals(3, three.recognizedConceptIds.size)
        assertTrue(three.candidates.isEmpty())
    }

    @Test
    fun pretrainedDiseaseModelLoadsAndRanksFourConceptComplaint() = runBlocking {
        val repository = repository()
        repository.initialize()

        assertTrue("Expected v2 pretrained model from APK assets", repository.isUsingPretrainedModel())

        val assessment = repository.assess(
            complaints = listOf(
                "пульс нерегулярный",
                "чувствую сердцебиение",
                "одышка",
                "голова кружится"
            )
        )

        assertEquals(4, assessment.recognizedConceptIds.size)
        assertEquals(DiseaseAssessmentStatus.RANKED, assessment.status)
        assertTrue("Expected non-empty cardiovascular top-5", assessment.candidates.isNotEmpty())
        assertTrue("Expected no more than five candidates", assessment.candidates.size <= 5)
        assertEquals("atrial_fibrillation", assessment.candidates.first().id)
    }

    private fun repository(): DiseaseNetworkRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return DiseaseNetworkRepository(context)
    }
}
