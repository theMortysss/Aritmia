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
import my.diplom.aritmia.nn.NetworkRepository
import my.diplom.aritmia.ui.screen.SharedViewModel
import my.diplom.aritmia.ui.screen.result.model.ResultScreenIntent
import my.diplom.aritmia.ui.screen.result.model.ResultScreenState
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class ResultViewModel @Inject constructor(
    private val db: AppDatabase,
    private val networkRepository: NetworkRepository
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

                    val symptoms  = db.symptomDao().getAllSymptoms()
                    val diagnosis = symptoms.lastOrNull { it.patientId == intent.userId }
                    val rules     = db.ruleDao().getAllRules()

                    val nnProb: Int? = if (diagnosis != null) {
                        diagnosis.nnProbability
                            ?: networkRepository.predict(
                                diagnosis.userInput.split(". ").filter { it.isNotBlank() }
                            )?.let { (it * 100).toInt() }
                    } else null

                    if (diagnosis != null && rules.isNotEmpty()) {
                        val userSymptoms  = diagnosis.userInput.split(". ").filter { it.isNotBlank() }
                        val medicalTerms  = diagnosis.medicalTerm?.split(", ")?.filter { it.isNotBlank() }
                            ?: emptyList()

                        val recognized   = mutableListOf<String>()
                        val unrecognized = mutableListOf<String>()
                        val recTerms     = mutableListOf<String>()

                        userSymptoms.forEach { s ->
                            val match = rules.find { s.contains(it.symptomKey, ignoreCase = true) }
                            if (match != null) {
                                val possible = buildList {
                                    add(match.medicalTerm)
                                    match.answerTriggers?.split(";")?.forEach { t ->
                                        t.split("=").getOrNull(1)?.let { add(it) }
                                    }
                                }
                                val term = medicalTerms.find { it in possible }
                                if (term != null) { recognized.add(s); recTerms.add(term) }
                                else unrecognized.add(s)
                            } else {
                                unrecognized.add(s)
                            }
                        }

                        _state.update {
                            it.copy(
                                diagnosis              = diagnosis,
                                rules                  = rules,
                                recognizedSymptoms     = recognized,
                                unrecognizedSymptoms   = unrecognized,
                                recognizedMedicalTerms = recTerms,
                                nnProbability          = nnProb,
                                isLoading              = false
                            )
                        }
                    } else {
                        _state.update { it.copy(isLoading = false) }
                    }
                }
            }

            is ResultScreenIntent.EditSymptom ->
                _state.update {
                    it.copy(
                        showDialog      = true,
                        selectedSymptom = intent.symptom,
                        editedSymptom   = intent.symptom,
                        suggestions     = emptyList()
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

            is ResultScreenIntent.SaveEditedSymptom -> {
                val selected = _state.value.selectedSymptom
                val edited   = _state.value.editedSymptom
                val diagnosis = _state.value.diagnosis
                val rules    = _state.value.rules

                if (edited.isNotBlank() && edited != selected && diagnosis != null) {
                    viewModelScope.launch {
                        val updatedInput = diagnosis.userInput.replace(selected!!, edited)
                        val updatedSymptoms = updatedInput.split(". ").filter { it.isNotBlank() }

                        // Пересчитываем нейросетевую вероятность
                        val newNnProb = networkRepository.predict(updatedSymptoms)
                            ?.let { (it * 100).toInt() }

                        val currentAnswers = buildAnswersMap(diagnosis.clarifyingAnswers)
                        currentAnswers.remove(selected)

                        val updatedDiagnoses = updatedSymptoms.map {
                            diagnoseSymptomResult(it, rules, currentAnswers)
                        }

                        val expertProb  = updatedDiagnoses.sumOf { it.probability }
                        val finalProb   = if (newNnProb != null)
                            ((expertProb + newNnProb) / 2).coerceIn(0, 100)
                        else expertProb

                        val updatedDiagnosis = diagnosis.copy(
                            userInput         = updatedInput,
                            medicalTerm       = updatedDiagnoses.mapNotNull { it.medicalTerm }
                                .joinToString(", "),
                            probability       = finalProb,
                            clarifyingAnswers = currentAnswers.entries
                                .filter { it.value.any { a -> a.isNotBlank() } }
                                .joinToString(";") { "${it.key}=${it.value.joinToString(",")}" },
                            nnProbability     = newNnProb
                        )
                        db.symptomDao().update(updatedDiagnosis)

                        val matchingRule = rules.find {
                            edited.contains(it.symptomKey, ignoreCase = true)
                        }
                        if (matchingRule?.clarifyingQuestions != null) {
                            sharedViewModel.setData(updatedSymptoms, diagnosis.patientId, currentAnswers)
                            _state.update { it.copy(navigateToClarify = true, showDialog = false) }
                        } else {
                            // Пересчитываем отображение
                            val recalcState = recalcDisplay(updatedDiagnosis, rules, newNnProb)
                            _state.update { recalcState.copy(showDialog = false) }
                        }
                    }
                } else {
                    _state.update { it.copy(showDialog = false) }
                }
            }

            is ResultScreenIntent.DismissDialog ->
                _state.update {
                    it.copy(
                        showDialog      = false,
                        selectedSymptom = null,
                        editedSymptom   = "",
                        suggestions     = emptyList()
                    )
                }

            is ResultScreenIntent.NavigateBack ->
                _state.update { it.copy(navigateBack = true) }

            is ResultScreenIntent.Logout ->
                _state.update { it.copy(logout = true) }

            is ResultScreenIntent.ResetNavigation ->
                _state.update { it.copy(navigateBack = false, navigateToClarify = false) }
        }
    }

    // ── Вспомогательные методы ─────────────────────────────────────────────────

    private fun buildAnswersMap(raw: String?): MutableMap<String, MutableList<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        raw?.split(";")?.filter { it.isNotBlank() }?.forEach { entry ->
            val (k, v) = entry.split("=")
            map[k] = v.split(",").filter { it.isNotBlank() }.toMutableList()
        }
        return map
    }

    private fun recalcDisplay(
        diag: my.diplom.aritmia.data.SymptomEntity,
        rules: List<RuleEntity>,
        nnProb: Int?
    ): ResultScreenState {
        val userSymptoms = diag.userInput.split(". ").filter { it.isNotBlank() }
        val medTerms     = diag.medicalTerm?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()

        val recognized   = mutableListOf<String>()
        val unrecognized = mutableListOf<String>()
        val recTerms     = mutableListOf<String>()

        userSymptoms.forEach { s ->
            val match = rules.find { s.contains(it.symptomKey, ignoreCase = true) }
            if (match != null) {
                val possible = buildList {
                    add(match.medicalTerm)
                    match.answerTriggers?.split(";")?.forEach { t ->
                        t.split("=").getOrNull(1)?.let { add(it) }
                    }
                }
                val term = medTerms.find { it in possible }
                if (term != null) { recognized.add(s); recTerms.add(term) }
                else unrecognized.add(s)
            } else unrecognized.add(s)
        }

        return _state.value.copy(
            diagnosis              = diag,
            recognizedSymptoms     = recognized,
            unrecognizedSymptoms   = unrecognized,
            recognizedMedicalTerms = recTerms,
            nnProbability          = nnProb
        )
    }
}

// ── Вспомогательные функции ────────────────────────────────────────────────────

data class DiagnosedSymptom(
    val userInput: String,
    val medicalTerm: String?,
    val probability: Int
)

fun diagnoseSymptomResult(
    symptom: String,
    rules: List<RuleEntity>,
    answers: Map<String, List<String>>
): DiagnosedSymptom {
    val match = rules.find { symptom.contains(it.symptomKey, ignoreCase = true) }
        ?: return DiagnosedSymptom(symptom, null, 0)

    val answersForSymptom = answers[match.symptomKey]?.filter { it.isNotBlank() } ?: emptyList()
    val updatedTerm = if (answersForSymptom.isNotEmpty() && match.answerTriggers != null) {
        match.answerTriggers.split(";").find { trigger ->
            val key = trigger.split("=").firstOrNull()
            answersForSymptom.any { it.equals(key, ignoreCase = true) }
        }?.split("=")?.getOrNull(1) ?: match.medicalTerm
    } else match.medicalTerm

    return DiagnosedSymptom(symptom, updatedTerm, match.probabilityWeight)
}
