package my.diplom.aritmia.diagnosis

import my.diplom.aritmia.data.AssessmentWorkflow

/**
 * Keeps doctor workflow decisions stable unless new safety information requires attention.
 * A newly detected triage flag always re-opens attention, even when the assessment had
 * previously been REVIEWED/CONTACTED/CLOSED. Model-only state changes keep the existing
 * doctor decision once the workflow has left NEW.
 */
object AssessmentAttentionPolicy {
    fun needsDoctorAttention(
        existingWorkflowStatus: String?,
        existingNeedsDoctorAttention: Boolean?,
        assessmentStatus: DiseaseAssessmentStatus,
        triageLevel: ComplaintTriageLevel
    ): Boolean {
        if (triageLevel != ComplaintTriageLevel.NONE) return true

        val modelNeedsAttention = assessmentStatus != DiseaseAssessmentStatus.RANKED
        val doctorAlreadyActed = existingWorkflowStatus != null &&
            existingWorkflowStatus != AssessmentWorkflow.NEW

        return if (doctorAlreadyActed) {
            existingNeedsDoctorAttention ?: modelNeedsAttention
        } else {
            modelNeedsAttention
        }
    }
}
