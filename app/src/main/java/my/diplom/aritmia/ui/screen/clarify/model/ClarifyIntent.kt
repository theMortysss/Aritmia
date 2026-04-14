package my.diplom.aritmia.ui.screen.clarify.model

sealed class ClarifyScreenIntent {
    data class Initialize(val symptoms: List<String>, val userId: Int, val initialAnswers: Map<String, List<String>>) : ClarifyScreenIntent()
    data class UpdateAnswer(val symptom: String, val questionIndex: Int, val answer: String) : ClarifyScreenIntent()
    object Finish : ClarifyScreenIntent()
    object Logout : ClarifyScreenIntent()
}