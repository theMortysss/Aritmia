package my.diplom.aritmia.ui.screen.result.model

sealed class ResultScreenIntent {
    data class LoadData(val userId: Int) : ResultScreenIntent()
    data class EditSymptom(val symptom: String) : ResultScreenIntent()
    data class UpdateEditedSymptom(val editedSymptom: String) : ResultScreenIntent()
    data class SelectSuggestion(val suggestion: String) : ResultScreenIntent()
    object SaveEditedSymptom : ResultScreenIntent()
    object DismissDialog : ResultScreenIntent()
    object NavigateBack : ResultScreenIntent()
    object Logout : ResultScreenIntent()
    object ResetNavigation : ResultScreenIntent()
}