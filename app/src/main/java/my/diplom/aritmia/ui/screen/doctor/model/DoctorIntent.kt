package my.diplom.aritmia.ui.screen.doctor.model

import java.time.LocalDateTime

internal val UNCHANGED_DATE_FILTER: LocalDateTime = LocalDateTime.MIN

internal fun resolveDateFilterUpdate(
    current: LocalDateTime?,
    requested: LocalDateTime?
): LocalDateTime? = if (requested == UNCHANGED_DATE_FILTER) current else requested

sealed class DoctorScreenIntent {
    data class ChangePage(val newPage: Int) : DoctorScreenIntent()
    data class ApplyFilters(
        val phone: String,
        val name: String,
        val minProbability: Int,
        val startDate: LocalDateTime?,
        val endDate: LocalDateTime?
    ) : DoctorScreenIntent()

    data class UpdateTempFilter(
        val phone: String? = null,
        val name: String? = null,
        val startDate: LocalDateTime? = UNCHANGED_DATE_FILTER,
        val endDate: LocalDateTime? = UNCHANGED_DATE_FILTER
    ) : DoctorScreenIntent()

    object ResetFilters : DoctorScreenIntent()
    object ShowFilterSheet : DoctorScreenIntent()
    object HideFilterSheet : DoctorScreenIntent()

    data class SetStatusFilter(val status: String) : DoctorScreenIntent()
    data class SetWorkflowFilter(val status: String) : DoctorScreenIntent()
    data class SetAttentionOnly(val enabled: Boolean) : DoctorScreenIntent()
    data class OpenAssessment(val assessmentId: Int) : DoctorScreenIntent()
    object CloseAssessment : DoctorScreenIntent()
    data class UpdateDoctorNote(val note: String) : DoctorScreenIntent()
    data class SaveAssessmentWorkflow(val workflowStatus: String) : DoctorScreenIntent()

    object Logout : DoctorScreenIntent()

    // Retained while legacy SymptomEntity UI is phased out.
    data class MarkPatientAsCalled(val symptomId: Int, val called: Boolean) : DoctorScreenIntent()
}
