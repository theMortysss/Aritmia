package my.diplom.aritmia.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplaintTriageTest {

    @Test
    fun suddenSpeechDisturbanceIsEmergencyEvenWithOneConcept() {
        val result = ComplaintTriage.assess(listOf("Внезапно стало трудно говорить"))

        assertEquals(ComplaintTriageLevel.EMERGENCY, result.level)
        assertTrue(result.flags.any { it.id == "sudden_neurologic_deficit" })
    }

    @Test
    fun acuteChestPainIsEmergencyWithoutWaitingForFourModelConcepts() {
        val extraction = FreeTextSymptomExtractor.extract(listOf("Сильная боль в груди сейчас"))
        assertTrue(extraction.modelConceptIds.size < DiseaseNetworkRepository.MIN_CONCEPTS_FOR_RANKING)

        val result = ComplaintTriage.assess(listOf("Сильная боль в груди сейчас"))

        assertEquals(ComplaintTriageLevel.EMERGENCY, result.level)
        assertTrue(result.flags.any { it.id == "acute_chest_symptoms" })
    }

    @Test
    fun lowBloodPressureAloneIsNotAutomaticallyEmergency() {
        val result = ComplaintTriage.assess(listOf("Низкое давление"))

        assertEquals(ComplaintTriageLevel.NONE, result.level)
    }

    @Test
    fun measuredSymptomaticHypotensionRequiresMedicalReview() {
        val result = ComplaintTriage.assess(
            complaints = listOf("Низкое давление"),
            clarificationAnswers = mapOf(
                "concept:low_bp:measured_bp" to "ниже 90/60",
                "concept:low_bp:symptomatic" to "да"
            )
        )

        assertEquals(ComplaintTriageLevel.MEDICAL_REVIEW, result.level)
        assertTrue(result.flags.any { it.id == "symptomatic_hypotension" })
    }

    @Test
    fun lowBloodPressureWithDizzinessNeedsReviewEvenWhenReadingIsUnknown() {
        val result = ComplaintTriage.assess(
            complaints = listOf("Низкое давление", "Кружится голова"),
            clarificationAnswers = mapOf(
                "concept:low_bp:measured_bp" to "не измерял(а) / не помню"
            )
        )

        assertEquals(ComplaintTriageLevel.MEDICAL_REVIEW, result.level)
        assertTrue(result.flags.any { it.id == "symptomatic_hypotension" })
    }

    @Test
    fun severeHypertensionWithChestPainIsEmergency() {
        val result = ComplaintTriage.assess(
            complaints = listOf("Высокое давление", "Боль в груди"),
            clarificationAnswers = mapOf(
                "concept:high_bp:measured_bp" to "180/120 или выше"
            )
        )

        assertEquals(ComplaintTriageLevel.EMERGENCY, result.level)
        assertTrue(result.flags.any { it.id == "hypertensive_emergency_pattern" })
    }

    @Test
    fun severeHypertensionWithoutRedFlagSymptomIsReviewNotEmergency() {
        val result = ComplaintTriage.assess(
            complaints = listOf("Высокое давление"),
            clarificationAnswers = mapOf(
                "concept:high_bp:measured_bp" to "180/120 или выше"
            )
        )

        assertEquals(ComplaintTriageLevel.MEDICAL_REVIEW, result.level)
        assertTrue(result.flags.any { it.id == "severe_hypertension_review" })
    }

    @Test
    fun exertionalSyncopeIsHighRiskMedicalReview() {
        val result = ComplaintTriage.assess(listOf("Во время нагрузки потерял сознание"))

        assertEquals(ComplaintTriageLevel.MEDICAL_REVIEW, result.level)
        assertTrue(result.flags.any { it.id == "high_risk_syncope" })
    }

    @Test
    fun ordinaryPalpitationsDoNotCreateEmergencyFlag() {
        val result = ComplaintTriage.assess(listOf("Сердце колотится"))

        assertEquals(ComplaintTriageLevel.NONE, result.level)
        assertTrue(result.flags.isEmpty())
    }
}
