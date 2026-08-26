package my.diplom.aritmia.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorMorphologyRobustnessTest {

    @Test
    fun inflectedPulseDescriptorsAreRecognized() {
        assertTrue("slow_heart_rate" in concepts("Пульс стал очень редким"))
        assertTrue("fast_heart_rate" in concepts("Пульс при этом был очень частым"))
    }

    @Test
    fun commonNaturalSyncopeAndDyspneaFormsAreRecognized() {
        val result = concepts("После боли в груди и нехватки воздуха потерял сознание")
        assertTrue("chest_pain" in result)
        assertTrue("dyspnea" in result)
        assertTrue("syncope" in result)
    }

    @Test
    fun breathingContextAcrossCommaPreservesPleuriticMeaning() {
        val result = concepts("Когда вдыхаю, боль в груди усиливается, трудно дышать")
        assertTrue("chest_pain" in result)
        assertTrue("pleuritic_pain" in result)
        assertTrue("dyspnea" in result)
    }

    @Test
    fun coordinatedPainLocationsAreExpandedConservatively() {
        val result = concepts("Боль в груди, спине и животе")
        assertTrue("chest_pain" in result)
        assertTrue("back_pain" in result)
        assertTrue("abdominal_pain" in result)
    }

    @Test
    fun newNaturalFormsPreserveExplicitNegation() {
        assertFalse("syncope" in concepts("Сознание не потерял"))
        assertFalse("dyspnea" in concepts("Дышать не трудно"))

        val nonPleuritic = concepts("Когда вдыхаю, боль в груди не усиливается")
        assertTrue("chest_pain" in nonPleuritic)
        assertFalse("pleuritic_pain" in nonPleuritic)

        val negatedCoordination = concepts("Боль в груди, спине и животе отсутствует")
        assertFalse("back_pain" in negatedCoordination)
        assertFalse("abdominal_pain" in negatedCoordination)
    }

    @Test
    fun conservativeSafetyRegressionsRemainIntact() {
        assertEquals(emptySet<String>(), concepts("Болит горло"))
        assertFalse("chest_pain" in concepts("Грудь не болит"))
        assertFalse("slow_heart_rate" in concepts("Пульс не редкий"))
        assertFalse("fast_heart_rate" in concepts("Пульс не частый"))
        assertTrue("chest_pain" in concepts("Боль в груди не проходит"))
        assertTrue("dyspnea" in concepts("Мне не хватает воздуха"))
    }

    private fun concepts(text: String): Set<String> =
        FreeTextSymptomExtractor.extract(listOf(text)).conceptIds
}
