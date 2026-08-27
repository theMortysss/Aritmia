package my.diplom.aritmia.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplaintOntologyTest {

    @Test
    fun lowBloodPressureIsRecognizedButIsNotInjectedIntoV2Model() {
        val extraction = FreeTextSymptomExtractor.extract(
            listOf("У меня низкое давление")
        )

        assertTrue("low_bp" in extraction.conceptIds)
        assertTrue(extraction.modelConceptIds.isEmpty())

        val vector = FreeTextSymptomExtractor.vectorize(listOf("У меня низкое давление"))
        assertEquals(47, vector.size)
        assertEquals(0.0, vector.sum(), 0.0)
    }

    @Test
    fun existingModelComplaintStillUsesItsOriginalFeature() {
        val extraction = FreeTextSymptomExtractor.extract(
            listOf("Сердце колотится")
        )

        assertTrue("palpitations" in extraction.conceptIds)
        assertEquals(setOf("palpitations"), extraction.modelConceptIds)
        assertEquals(1.0, FreeTextSymptomExtractor.vectorize(listOf("Сердце колотится")).sum(), 0.0)
    }

    @Test
    fun lowBloodPressureSuggestionIsAvailableInRussian() {
        val suggestions = ComplaintOntology.suggestions("низк")
        assertTrue(suggestions.any { it.equals("низкое давление", ignoreCase = true) })
    }

    @Test
    fun negatedLowBloodPressureDoesNotCreateConcept() {
        val extraction = FreeTextSymptomExtractor.extract(
            listOf("Давление не низкое")
        )
        assertFalse("low_bp" in extraction.conceptIds)
    }

    @Test
    fun ontologyCanGrowWithoutChangingPretrainedModelContract() {
        assertEquals(47, DiseaseCatalog.concepts.size)
        assertEquals(47, ComplaintOntology.modelConceptIds.size)
        assertTrue(ComplaintOntology.concepts.size > DiseaseCatalog.concepts.size)
        assertTrue(
            ComplaintOntology.concepts
                .mapNotNull { it.modelConceptId }
                .all { it in DiseaseCatalog.concepts.map { model -> model.id }.toSet() }
        )
    }
}
