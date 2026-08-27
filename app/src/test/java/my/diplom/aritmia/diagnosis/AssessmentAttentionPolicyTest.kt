package my.diplom.aritmia.diagnosis

import my.diplom.aritmia.data.AssessmentWorkflow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentAttentionPolicyTest {

    @Test
    fun newEmergencyReopensPreviouslyReviewedAssessment() {
        assertTrue(
            AssessmentAttentionPolicy.needsDoctorAttention(
                existingWorkflowStatus = AssessmentWorkflow.REVIEWED,
                existingNeedsDoctorAttention = false,
                assessmentStatus = DiseaseAssessmentStatus.RANKED,
                triageLevel = ComplaintTriageLevel.EMERGENCY
            )
        )
    }

    @Test
    fun medicalReviewReopensPreviouslyClosedAssessment() {
        assertTrue(
            AssessmentAttentionPolicy.needsDoctorAttention(
                existingWorkflowStatus = AssessmentWorkflow.CLOSED,
                existingNeedsDoctorAttention = false,
                assessmentStatus = DiseaseAssessmentStatus.RANKED,
                triageLevel = ComplaintTriageLevel.MEDICAL_REVIEW
            )
        )
    }

    @Test
    fun rankedAssessmentWithoutTriagePreservesDoctorDecision() {
        assertFalse(
            AssessmentAttentionPolicy.needsDoctorAttention(
                existingWorkflowStatus = AssessmentWorkflow.CONTACTED,
                existingNeedsDoctorAttention = false,
                assessmentStatus = DiseaseAssessmentStatus.RANKED,
                triageLevel = ComplaintTriageLevel.NONE
            )
        )
    }

    @Test
    fun insufficientNewAssessmentNeedsDoctorAttention() {
        assertTrue(
            AssessmentAttentionPolicy.needsDoctorAttention(
                existingWorkflowStatus = null,
                existingNeedsDoctorAttention = null,
                assessmentStatus = DiseaseAssessmentStatus.INSUFFICIENT_EVIDENCE,
                triageLevel = ComplaintTriageLevel.NONE
            )
        )
    }
}
