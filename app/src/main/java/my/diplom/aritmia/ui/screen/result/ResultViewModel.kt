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
import my.diplom.aritmia.diagnosis.DiseaseNetworkRepository
import my.diplom.aritmia.ui.screen.SharedViewModel
import my.diplom.aritmia.ui.screen.clarify.resolveSymptomTerm
import my.diplom.aritmia.ui.screen.result.model.ResultScreenIntent
import my.diplom.aritmia.ui.screen.result.model.ResultScreenState
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class ResultViewModel @Inject constructor(
    private val db: AppDatabase,
    private val diseaseNetworkRepository: DiseaseNetworkRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ResultScreenState())
    val state: StateFlow<ResultScreenState> = _state.asStateFlow()

    private lateinit var sharedViewModel: SharedViewModel

    fun setSharedViewModel(vm: SharedViewModel) { sharedViewModel = vm }

    fun onIntent(intent: ResultScreenIntent) {
        when (intent) {
            is ResultScreenIntent.LoadData -> loadData(intent.userId)
            is ResultScreenIntent.EditSymptom -> _state.update {
                it.copy(
                    showDialog = true,
                    selectedSymptom = intent.symptom,
                    editedSymptom = intent.symptom,
                    suggestions = emptyList()
                )
            }
            is ResultScreenIntent.UpdateEditedSymptom -> {
                val suggestions = if (intent.editedSymptom.isNotBlank()) {
                    _state.value.rules.filter {
                        it.symptomKey.contains(intent.editedSymptom, ignoreCase = true) ||
                            it.medicalTerm.contains(intent.editedSymptom, ignoreCase = true)
                    }.map { it.symptomKey }.distinct()
                } else emptyList()
                _state.update { it.copy(editedSymptom = intent.editedSymptom, suggestions = suggestions) }
            }
            is ResultScreenIntent.SelectSuggestion ->
                _state.update { it.copy(editedSymptom = intent.suggestion, suggestions = emptyList()) }
            is ResultScreenIntent.SaveEditedSymptom -> saveEditedSymptom()
            is ResultScreenIntent.DismissDialog -> _state.update {
                it.copy(showDialog = false, selectedSymptom = null, editedSymptom = "", suggestions = emptyList())
            }
            is ResultScreenIntent.NavigateBack -> _state.update { it.copy(navigateBack = true) }
            is ResultScreenIntent.Logout -> _state.update { it.copy(logout = true) }
            is ResultScreenIntent.ResetNavigation ->
                _state.update { it.copy(navigateBack = false, navigateToClarify = false) }
        }
    }

    private fun loadData(userId: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val allSymptoms = db.symptomDao().getAllSymptoms()
            val diagnosis = allSymptoms.lastOrNull { it.patientId == userId }
            val rules = db.ruleDao().getAllRules()

            if (diagnosis != null && rules.isNotEmpty()) {
                val (recognized, unrecognized, recTerms) =
                    classifySymptoms(diagnosis.userInput, diagnosis.medicalTerm, rules)

                val userComplaints = diagnosis.userInput.split(". ").filter { it.isNotBlank() }
                val assessment = diseaseNetworkRepository.assess(
                    complaints = userComplaints,
                    limit = 5
                )

                _state.update {
                    it.copy(
                        diagnosis = diagnosis,
                        rules = rules,
                        recognizedSymptoms = recognized,
                        unrecognizedSymptoms = unrecognized,
                        recognizedMedicalTerms = recTerms,
                        diseaseAssessmentStatus = assessment.status,
                        recognizedDiseaseConceptCount = assessment.recognizedConceptIds.size,
                        diseaseCandidates = assessment.candidates,
                        isLoading = false
                    )
                }
            } else {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun saveEditedSymptom() {
        val selected = _state.value.selectedSymptom ?: return
        val edited = _state.value.editedSymptom
        val diagnosis = _state.value.diagnosis ?: return
        val rules = _state.value.rules

        if (edited.isBlank() || edited == selected) {
            _state.update { it.copy(showDialog = false) }
            return
        }

        viewModelScope.launch {
            val updatedInput = diagnosis.userInput.replace(selected, edited)
            val updatedSymptoms = updatedInput.split(". ").filter { it.isNotBlank() }

            val answers = parseAnswers(diagnosis.clarifyingAnswers).toMutableMap()
            answers.remove(selected)

            val newMedTerms = updatedSymptoms.mapNotNull { s ->
                resolveSymptomTerm(s, rules, answers).medicalTerm
            }.joinToString(", ")

            // Legacy probability fields remain in Room only for schema/history compatibility.
            // They are no longer recomputed by the retired binary MLP.
            val updatedDiagnosis = diagnosis.copy(
                userInput = updatedInput,
                medicalTerm = newMedTerms.ifBlank { null },
                clarifyingAnswers = answers.entries
                    .filter { it.value.any { a -> a.isNotBlank() } }
                    .joinToString(";") { "${it.key}=${it.value.joinToString(",")}" },
                nnProbability = null
            )
            db.symptomDao().update(updatedDiagnosis)

            val matchingRule = rules.find { edited.contains(it.symptomKey, ignoreCase = true) }
            if (matchingRule?.clarifyingQuestions != null) {
                sharedViewModel.setData(updatedSymptoms, diagnosis.patientId, answers)
                _state.update { it.copy(navigateToClarify = true, showDialog = false) }
            } else {
                val (recognized, unrecognized, recTerms) =
                    classifySymptoms(updatedDiagnosis.userInput, updatedDiagnosis.medicalTerm, rules)
                val assessment = diseaseNetworkRepository.assess(
                    complaints = updatedSymptoms,
                    limit = 5
                )
                _state.update {
                    it.copy(
                        diagnosis = updatedDiagnosis,
                        recognizedSymptoms = recognized,
                        unrecognizedSymptoms = unrecognized,
                        recognizedMedicalTerms = recTerms,
                        diseaseAssessmentStatus = assessment.status,
                        recognizedDiseaseConceptCount = assessment.recognizedConceptIds.size,
                        diseaseCandidates = assessment.candidates,
                        showDialog = false
                    )
                }
            }
        }
    }

    private data class SymptomClassification(
        val recognized: List<String>,
        val unrecognized: List<String>,
        val recognizedTerms: List<String>
    )

    private fun classifySymptoms(
        userInput: String,
        medicalTermRaw: String?,
        rules: List<RuleEntity>
    ): SymptomClassification {
        val userSymptoms = userInput.split(". ").filter { it.isNotBlank() }
        val savedTerms = medicalTermRaw?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()
        val recognized = mutableListOf<String>()
        val unrecognized = mutableListOf<String>()
        val recTerms = mutableListOf<String>()

        userSymptoms.forEach { s ->
            val rule = rules.find { s.contains(it.symptomKey, ignoreCase = true) }
            if (rule != null) {
                val possible = buildList {
                    add(rule.medicalTerm)
                    rule.answerTriggers?.split(";")?.forEach { t ->
                        t.split("=").getOrNull(1)?.let { add(it) }
                    }
                }
                val term = savedTerms.find { it in possible }
                if (term != null) {
                    recognized.add(s)
                    recTerms.add(term)
                } else {
                    unrecognized.add(s)
                }
            } else {
                unrecognized.add(s)
            }
        }
        return SymptomClassification(recognized, unrecognized, recTerms)
    }

    private fun parseAnswers(raw: String?): Map<String, MutableList<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        raw?.split(";")?.filter { it.isNotBlank() }?.forEach { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                map[parts[0]] = parts[1].split(",")
                    .filter { it.isNotBlank() }
                    .toMutableList()
            }
        }
        return map
    }
}
