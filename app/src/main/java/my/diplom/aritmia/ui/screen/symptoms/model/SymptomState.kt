package my.diplom.aritmia.ui.screen.symptoms.model

import androidx.compose.runtime.Immutable
import my.diplom.aritmia.data.RuleEntity

@Immutable
data class SymptomsScreenState(
    val symptoms: List<String> = emptyList(),
    val newSymptom: String = "",
    val suggestions: List<String> = emptyList(),
    val rules: List<RuleEntity> = emptyList(),
    val patientId: Int = -1,
    val showDeleteDialog: String? = null,
    val isDiagnosed: Boolean = false,
    val navigateToDiagnose: Boolean = false,
    val isLoading: Boolean = true,
    val logout: Boolean = false
)
