package my.diplom.aritmia.ui.screen.result

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
import my.diplom.aritmia.ui.screen.SharedViewModel
import my.diplom.aritmia.ui.screen.result.model.ResultScreenIntent
import my.diplom.aritmia.ui.screen.result.model.ResultScreenState
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class ResultViewModel @Inject constructor(
    private val db: AppDatabase
) : ViewModel() {
    private val _state = MutableStateFlow(ResultScreenState())
    val state: StateFlow<ResultScreenState> = _state.asStateFlow()

    private lateinit var sharedViewModel: SharedViewModel

    fun setSharedViewModel(sharedViewModel: SharedViewModel) {
        this.sharedViewModel = sharedViewModel
    }

    fun onIntent(intent: ResultScreenIntent) {
        when (intent) {
            is ResultScreenIntent.LoadData -> {
                viewModelScope.launch {
                    _state.update { it.copy(isLoading = true) }
                    val symptoms = db.symptomDao().getAllSymptoms()
                    val diagnosis = symptoms.lastOrNull { it.patientId == intent.userId }
                    val rules = db.ruleDao().getAllRules()

                    if (diagnosis != null && rules.isNotEmpty()) {
                        val userSymptoms = diagnosis.userInput.split(". ").filter { it.isNotBlank() }
                        val medicalTerms = diagnosis.medicalTerm?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()

                        val recognizedSymptoms = mutableListOf<String>()
                        val unrecognizedSymptoms = mutableListOf<String>()
                        val recognizedMedicalTerms = mutableListOf<String>()

                        userSymptoms.forEach { userSymptom ->
                            val matchingRule = rules.find { rule ->
                                userSymptom.contains(rule.symptomKey, ignoreCase = true)
                            }
                            if (matchingRule != null) {
                                val possibleTerms = mutableListOf(matchingRule.medicalTerm)
                                matchingRule.answerTriggers?.split(";")?.forEach {
                                    val term = it.split("=").getOrNull(1)
                                    if (term != null) possibleTerms.add(term)
                                }
                                val isRecognized = medicalTerms.any { term ->
                                    possibleTerms.contains(term)
                                }
                                if (isRecognized) {
                                    recognizedSymptoms.add(userSymptom)
                                    val recognizedTerm = medicalTerms.find { term ->
                                        possibleTerms.contains(term)
                                    }
                                    if (recognizedTerm != null) {
                                        recognizedMedicalTerms.add(recognizedTerm)
                                    }
                                } else {
                                    unrecognizedSymptoms.add(userSymptom)
                                }
                            } else {
                                unrecognizedSymptoms.add(userSymptom)
                            }
                        }

                        _state.update {
                            it.copy(
                                diagnosis = diagnosis,
                                rules = rules,
                                recognizedSymptoms = recognizedSymptoms,
                                unrecognizedSymptoms = unrecognizedSymptoms,
                                recognizedMedicalTerms = recognizedMedicalTerms,
                                isLoading = false
                            )
                        }
                    } else {
                        _state.update { it.copy(isLoading = false) }
                    }
                }
            }
            is ResultScreenIntent.EditSymptom -> {
                _state.update {
                    it.copy(
                        showDialog = true,
                        selectedSymptom = intent.symptom,
                        editedSymptom = intent.symptom,
                        suggestions = emptyList()
                    )
                }
            }
            is ResultScreenIntent.UpdateEditedSymptom -> {
                val suggestions = if (intent.editedSymptom.isNotBlank()) {
                    _state.value.rules
                        .filter { rule ->
                            rule.symptomKey.contains(intent.editedSymptom, ignoreCase = true) ||
                                    rule.medicalTerm.contains(intent.editedSymptom, ignoreCase = true)
                        }
                        .map { rule -> rule.symptomKey }
                        .distinct()
                } else {
                    emptyList()
                }
                _state.update {
                    it.copy(
                        editedSymptom = intent.editedSymptom,
                        suggestions = suggestions
                    )
                }
            }
            is ResultScreenIntent.SelectSuggestion -> {
                _state.update {
                    it.copy(
                        editedSymptom = intent.suggestion,
                        suggestions = emptyList()
                    )
                }
            }
            is ResultScreenIntent.SaveEditedSymptom -> {
                val selectedSymptom = _state.value.selectedSymptom
                val editedSymptom = _state.value.editedSymptom
                val diagnosis = _state.value.diagnosis
                val rules = _state.value.rules

                if (editedSymptom.isNotBlank() && editedSymptom != selectedSymptom && diagnosis != null) {
                    viewModelScope.launch {
                        val currentAnswers = mutableMapOf<String, MutableList<String>>()
                        diagnosis.clarifyingAnswers?.split(";")?.filter { it.isNotBlank() }?.forEach { entry ->
                            val (symptom, answersStr) = entry.split("=")
                            currentAnswers[symptom] = answersStr.split(",").filter { it.isNotBlank() }.toMutableList()
                        }

                        val updatedUserInput = diagnosis.userInput.replace(selectedSymptom!!, editedSymptom)
                        val updatedSymptoms = updatedUserInput.split(". ").filter { it.isNotBlank() }
                        currentAnswers.remove(selectedSymptom)
                        if (currentAnswers.containsKey(editedSymptom)) {
                            currentAnswers.remove(editedSymptom)
                        }

                        val updatedDiagnoses = updatedSymptoms.map { symptom ->
                            diagnoseSymptom(symptom, rules, currentAnswers)
                        }

                        val updatedDiagnosis = diagnosis.copy(
                            userInput = updatedUserInput,
                            medicalTerm = updatedDiagnoses.mapNotNull { it.medicalTerm }.joinToString(", "),
                            probability = updatedDiagnoses.sumOf { it.probability },
                            clarifyingAnswers = currentAnswers.entries
                                .filter { it.value.any { answer -> answer.isNotBlank() } }
                                .joinToString(";") { "${it.key}=${it.value.joinToString(",")}" }
                        )

                        db.symptomDao().update(updatedDiagnosis)

                        val matchingRule = rules.find { editedSymptom.contains(it.symptomKey, ignoreCase = true) }
                        if (matchingRule != null && matchingRule.clarifyingQuestions != null) {
                            sharedViewModel.setData(updatedSymptoms, diagnosis.patientId, currentAnswers)
                            _state.update { it.copy(navigateToClarify = true, showDialog = false) }
                        } else {
                            val userSymptoms = updatedUserInput.split(". ").filter { it.isNotBlank() }
                            val medicalTerms = updatedDiagnosis.medicalTerm?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()

                            val recognizedSymptoms = mutableListOf<String>()
                            val unrecognizedSymptoms = mutableListOf<String>()
                            val recognizedMedicalTerms = mutableListOf<String>()

                            userSymptoms.forEach { userSymptom ->
                                val matchingRule = rules.find { rule ->
                                    userSymptom.contains(rule.symptomKey, ignoreCase = true)
                                }
                                if (matchingRule != null) {
                                    val possibleTerms = mutableListOf(matchingRule.medicalTerm)
                                    matchingRule.answerTriggers?.split(";")?.forEach {
                                        val term = it.split("=").getOrNull(1)
                                        if (term != null) possibleTerms.add(term)
                                    }
                                    val isRecognized = medicalTerms.any { term ->
                                        possibleTerms.contains(term)
                                    }
                                    if (isRecognized) {
                                        recognizedSymptoms.add(userSymptom)
                                        val recognizedTerm = medicalTerms.find { term ->
                                            possibleTerms.contains(term)
                                        }
                                        if (recognizedTerm != null) {
                                            recognizedMedicalTerms.add(recognizedTerm)
                                        }
                                    } else {
                                        unrecognizedSymptoms.add(userSymptom)
                                    }
                                } else {
                                    unrecognizedSymptoms.add(userSymptom)
                                }
                            }

                            _state.update {
                                it.copy(
                                    diagnosis = updatedDiagnosis,
                                    recognizedSymptoms = recognizedSymptoms,
                                    unrecognizedSymptoms = unrecognizedSymptoms,
                                    recognizedMedicalTerms = recognizedMedicalTerms,
                                    showDialog = false
                                )
                            }
                        }
                    }
                } else {
                    _state.update { it.copy(showDialog = false) }
                }
            }
            is ResultScreenIntent.DismissDialog -> {
                _state.update {
                    it.copy(
                        showDialog = false,
                        selectedSymptom = null,
                        editedSymptom = "",
                        suggestions = emptyList()
                    )
                }
            }
            is ResultScreenIntent.NavigateBack -> {
                _state.update { it.copy(navigateBack = true) }
            }
            is ResultScreenIntent.Logout -> {
                _state.update { it.copy(logout = true) }
            }
            is ResultScreenIntent.ResetNavigation -> {
                _state.update { it.copy(navigateBack = false, navigateToClarify = false) }
            }
        }
    }
}

data class DiagnosedSymptom(
    val userInput: String,
    val medicalTerm: String?,
    val probability: Int
)

fun diagnoseSymptom(
    symptom: String,
    rules: List<RuleEntity>,
    answers: Map<String, List<String>>
): DiagnosedSymptom {
    val matchingRule = rules.find { rule ->
        symptom.contains(rule.symptomKey, ignoreCase = true)
    }
    return if (matchingRule != null) {
        val baseTerm = matchingRule.medicalTerm
        val probability = matchingRule.probabilityWeight
        val answersForSymptom = answers[matchingRule.symptomKey]?.filter { it.isNotBlank() } ?: emptyList()

        val updatedTerm = if (answersForSymptom.isNotEmpty() && matchingRule.answerTriggers != null) {
            matchingRule.answerTriggers.split(";").find { trigger ->
                val triggerAnswer = trigger.split("=").firstOrNull()
                answersForSymptom.any { answer -> answer.equals(triggerAnswer, ignoreCase = true) }
            }?.split("=")?.getOrNull(1) ?: baseTerm
        } else {
            baseTerm
        }

        DiagnosedSymptom(symptom, updatedTerm, probability)
    } else {
        DiagnosedSymptom(symptom, null, 0)
    }
}