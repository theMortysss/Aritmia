package my.diplom.aritmia.ui.screen.symptoms.model

sealed class SymptomsScreenIntent {
    data class UpdateNewSymptom(val newSymptom: String) : SymptomsScreenIntent()
    data class SelectSuggestion(val suggestion: String) : SymptomsScreenIntent()
    object AddSymptom : SymptomsScreenIntent()
    data class ShowDeleteDialog(val symptom: String) : SymptomsScreenIntent()
    object ConfirmDelete : SymptomsScreenIntent()
    object DismissDeleteDialog : SymptomsScreenIntent()
    object Diagnose : SymptomsScreenIntent()
    object Logout : SymptomsScreenIntent()
}