package my.diplom.aritmia.ui.screen.clarify

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.ui.screen.clarify.model.ClarifyScreenIntent
import my.diplom.aritmia.ui.screen.clarify.model.ClarifyScreenState
import javax.inject.Inject

@HiltViewModel
class ClarifyViewModel @Inject constructor(
    private val db: AppDatabase
) : ViewModel() {
    private val _state = MutableStateFlow(ClarifyScreenState())
    val state: StateFlow<ClarifyScreenState> = _state.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.O)
    fun onIntent(intent: ClarifyScreenIntent) {
        when (intent) {
            is ClarifyScreenIntent.Initialize -> {
                _state.update {
                    it.copy(
                        symptoms = intent.symptoms,
                        userId = intent.userId,
                        answers = intent.initialAnswers.toMutableMap().mapValues { entry ->
                            entry.value.toMutableList()
                        },
                        isLoading = true
                    )
                }
                viewModelScope.launch {
                    val rules = db.ruleDao().getAllRules()
                    _state.update { it.copy(rules = rules, isLoading = false) }
                }
            }
            is ClarifyScreenIntent.UpdateAnswer -> {
                val currentAnswers = _state.value.answers.toMutableMap()
                val symptomAnswers = currentAnswers[intent.symptom]?.toMutableList() ?: mutableListOf()

                while (symptomAnswers.size <= intent.questionIndex) {
                    symptomAnswers.add("")
                }
                symptomAnswers[intent.questionIndex] = intent.answer
                currentAnswers[intent.symptom] = symptomAnswers
                _state.update { it.copy(answers = currentAnswers) }
            }
            is ClarifyScreenIntent.Finish -> {
                val symptoms = _state.value.symptoms
                val rules = _state.value.rules
                val answers = _state.value.answers

                val diagnoses = symptoms.map { symptom ->
                    diagnoseSymptom(symptom, rules, answers)
                }
                _state.update { it.copy(navigateToFinish = true) }
            }
            is ClarifyScreenIntent.Logout -> {
                _state.update { it.copy(logout = true) }
            }
        }
    }
}

data class Diagnosis(
    val userInput: String,
    val medicalTerm: String?,
    val probability: Int
)

fun diagnoseSymptom(
    symptom: String,
    rules: List<RuleEntity>,
    answers: Map<String, List<String>>
): Diagnosis {
    val rule = rules.find { rule -> symptom.contains(rule.symptomKey, ignoreCase = true) }
    val symptomAnswers = answers[symptom] ?: emptyList()

    if (rule == null) {
        return Diagnosis(
            userInput = symptom,
            medicalTerm = "Нераспознанный симптом",
            probability = 0
        )
    }

    var medicalTerm = rule.medicalTerm
    var probability = rule.probabilityWeight

    rule.clarifyingQuestions?.split(";")?.filter { it.isNotBlank() }?.forEachIndexed { index, question ->
        val answer = symptomAnswers.getOrNull(index)
        if (answer != null && answer != "не могу ответить") {
            rule.answerTriggers?.split(";")?.forEach { trigger ->
                val (triggerAnswer, newTerm) = trigger.split("=")
                if (answer == triggerAnswer) {
                    medicalTerm = newTerm
                    println("Symptom: $symptom, Question: $question, Answer: $answer, New Medical Term: $newTerm")
                }
            }
        }
    }

    return Diagnosis(
        userInput = symptom,
        medicalTerm = medicalTerm,
        probability = probability
    )
}