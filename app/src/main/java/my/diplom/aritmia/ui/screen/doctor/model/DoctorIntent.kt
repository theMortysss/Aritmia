package my.diplom.aritmia.ui.screen.doctor.model

import my.diplom.aritmia.data.RuleEntity
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

    /**
     * UNCHANGED_DATE_FILTER means that a date field was not supplied and must stay unchanged.
     * Explicit null means that the user cleared that date filter.
     */
    data class UpdateTempFilter(
        val phone: String? = null,
        val name: String? = null,
        val startDate: LocalDateTime? = UNCHANGED_DATE_FILTER,
        val endDate: LocalDateTime? = UNCHANGED_DATE_FILTER
    ) : DoctorScreenIntent()

    object ResetFilters : DoctorScreenIntent()
    object ShowFilterSheet : DoctorScreenIntent()
    object HideFilterSheet : DoctorScreenIntent()
    data class ChangeTab(val tabIndex: Int) : DoctorScreenIntent()
    object ShowRuleEditor : DoctorScreenIntent()
    object HideRuleEditor : DoctorScreenIntent()
    data class SelectRule(val rule: RuleEntity?) : DoctorScreenIntent()
    data class SaveRule(val rule: RuleEntity) : DoctorScreenIntent()
    data class DeleteRule(val rule: RuleEntity) : DoctorScreenIntent()
    object Logout : DoctorScreenIntent()
    data class MarkPatientAsCalled(val symptomId: Int, val called: Boolean) : DoctorScreenIntent()
}
