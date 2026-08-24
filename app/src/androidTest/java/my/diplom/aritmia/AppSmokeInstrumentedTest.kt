package my.diplom.aritmia

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
    fun pretrainedDiseaseModelLoadsFromApkAndClassifiesRussianComplaint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DiseaseNetworkRepository(context)

        repository.initialize()

        assertTrue("Expected v2 pretrained model from APK assets", repository.isUsingPretrainedModel())

        val candidates = repository.classify(
            complaints = listOf(
                "сердце бьется неровно",
                "чувствую сердцебиение",
                "одышка"
            )
        )

        assertTrue("Expected non-empty cardiovascular top-5", candidates.isNotEmpty())
        assertTrue("Expected no more than five candidates", candidates.size <= 5)
        assertEquals("atrial_fibrillation", candidates.first().id)
    }
}
