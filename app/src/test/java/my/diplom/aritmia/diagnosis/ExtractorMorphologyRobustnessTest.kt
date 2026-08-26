package my.diplom.aritmia.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorMorphologyRobustnessTest {

    @Test
    fun inflectedPulseDescriptorsAreRecognized() {
        assertTrue(concepts("Пульс стал очень редким") contains "slow_heart_rate")
        assertTrue(concepts("Пульс при этом был очень частым") contains "fast_heart_rate")
    }

    @Test
    fun commonNaturalSyncopeAndDyspneaFormsAreRecognized() {
        val result = concepts("После боли в груди и нехватки воздуха потерял сознание")
        assertTrue(result contains "chest_pain")
        assertTrue(result contains "dyspnea")
        assertTrue(result contains "syncope")
    }

    @Test
    fun breathingContextAcrossCommaPreservesPleuriticMeaning() {
        val result = concepts("Когда вдыхаю, боль в груди усиливается, трудно дышать")
        assertTrue(result contains "chest_pain")
        assertTrue(result contains "pleuritic_pain")
        assertTrue(result contains "dyspnea")
    }

    @Test
    fun coordinatedPainLocationsAreExpandedConservatively() {
        val result = concepts("Боль в груди, спине и животе")
        assertTrue(result contains "chest_pain")
        assertTrue(result contains "back_pain")
        assertTrue(result contains "abdominal_pain")
    }

    @Test
    fun conservativeSafetyRegressionsRemainIntact() {
        assertEquals(emptySet<String>(), concepts("Болит горло"))
        assertFalse(concepts("Грудь не болит") contains "chest_pain")
        assertFalse(concepts("Пульс не редкий") contains "slow_heart_rate")
        assertFalse(concepts("Пульс не частый") contains "fast_heart_rate")
        assertTrue(concepts("Боль в груди не проходит") contains "chest_pain")
        assertTrue(concepts("Мне не хватает воздуха") contains "dyspnea")
    }

    private fun concepts(text: String): Set<String> =
        FreeTextSymptomExtractor.extract(listOf(text)).conceptIds
}
