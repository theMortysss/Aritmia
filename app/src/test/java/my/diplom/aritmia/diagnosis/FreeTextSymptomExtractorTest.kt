package my.diplom.aritmia.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTextSymptomExtractorTest {

    @Test
    fun positiveChestPainIsRecognized() {
        val result = FreeTextSymptomExtractor.extract(listOf("У меня боль в груди"))
        assertTrue("chest_pain" in result.conceptIds)
    }

    @Test
    fun explicitAbsentChestPainIsNotRecognized() {
        val result = FreeTextSymptomExtractor.extract(listOf("Боль в груди отсутствует"))
        assertFalse("chest_pain" in result.conceptIds)
    }

    @Test
    fun reorderedNegatedVerbIsNotRecognized() {
        val result = FreeTextSymptomExtractor.extract(listOf("Грудь не болит"))
        assertFalse("chest_pain" in result.conceptIds)
    }

    @Test
    fun symptomThatDoesNotGoAwayRemainsPositive() {
        val result = FreeTextSymptomExtractor.extract(listOf("Боль в груди не проходит"))
        assertTrue("chest_pain" in result.conceptIds)
    }

    @Test
    fun mixedComplaintKeepsPositiveSymptomOnly() {
        val result = FreeTextSymptomExtractor.extract(
            listOf("Грудь не болит, но сердце колотится")
        )
        assertFalse("chest_pain" in result.conceptIds)
        assertTrue("palpitations" in result.conceptIds)
    }

    @Test
    fun throatPainDoesNotInventCardiovascularConcept() {
        val result = FreeTextSymptomExtractor.extract(listOf("болит горло"))
        assertTrue(result.conceptIds.isEmpty())
    }

    @Test
    fun sparseChestPainProducesOnlyChestPainConcept() {
        val result = FreeTextSymptomExtractor.extract(listOf("болит грудь"))
        assertEquals(setOf("chest_pain"), result.conceptIds)
    }
}
