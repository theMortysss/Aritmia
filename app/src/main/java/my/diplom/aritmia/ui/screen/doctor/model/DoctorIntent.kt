package my.diplom.aritmia.ui.screen.doctor.model

import my.diplom.aritmia.data.RuleEntity
import java.time.LocalDateTime

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
        val minProbability: Int? = null,
        val startDate: LocalDateTime? = null,
        val endDate: LocalDateTime? = null
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