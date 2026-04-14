package my.diplom.aritmia.ui.screen.symptoms

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.SymptomEntity
import my.diplom.aritmia.ui.screen.symptoms.model.SymptomsScreenIntent
import my.diplom.aritmia.ui.screen.symptoms.model.SymptomsScreenState
import java.time.LocalDateTime
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class SymptomsViewModel @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(SymptomsScreenState())
    val state: StateFlow<SymptomsScreenState> = _state.asStateFlow()

    init {
        val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val patientId = sharedPreferences.getInt("current_patient_id", -1)
        _state.update { it.copy(patientId = patientId) }

        viewModelScope.launch {
            val rules = db.ruleDao().getAllRules()
            _state.update { it.copy(rules = rules, isLoading = false) }
        }
    }

    fun onIntent(intent: SymptomsScreenIntent) {
        when (intent) {
            is SymptomsScreenIntent.UpdateNewSymptom -> {
                val newSymptom = intent.newSymptom
                val suggestions = if (newSymptom.isNotBlank()) {
                    _state.value.rules
                        .map { it.symptomKey }
                        .filter { it.contains(newSymptom, ignoreCase = true) }
                        .distinct()
                        .sorted()
                } else {
                    emptyList()
                }
                _state.update { it.copy(newSymptom = newSymptom, suggestions = suggestions) }
            }
            is SymptomsScreenIntent.SelectSuggestion -> {
                _state.update {
                    it.copy(
                        newSymptom = intent.suggestion,
                        suggestions = emptyList(),
                        isDiagnosed = false
                    )
                }
            }
            is SymptomsScreenIntent.AddSymptom -> {
                val newSymptom = _state.value.newSymptom
                if (newSymptom.isNotBlank()) {
                    _state.update {
                        it.copy(
                            symptoms = it.symptoms + newSymptom,
                            newSymptom = "",
                            suggestions = emptyList(),
                            isDiagnosed = false
                        )
                    }
                }
            }
            is SymptomsScreenIntent.ShowDeleteDialog -> {
                _state.update { it.copy(showDeleteDialog = intent.symptom) }
            }
            is SymptomsScreenIntent.ConfirmDelete -> {
                val symptomToDelete = _state.value.showDeleteDialog
                if (symptomToDelete != null) {
                    _state.update {
                        it.copy(
                            symptoms = it.symptoms.filter { it != symptomToDelete },
                            showDeleteDialog = null,
                            isDiagnosed = false
                        )
                    }
                }
            }
            is SymptomsScreenIntent.DismissDeleteDialog -> {
                _state.update { it.copy(showDeleteDialog = null) }
            }
            is SymptomsScreenIntent.Diagnose -> {
                val symptoms = _state.value.symptoms
                val rules = _state.value.rules
                val patientId = _state.value.patientId

                val hasQuestions = symptoms.any { symptom ->
                    rules.any { rule ->
                        symptom.contains(rule.symptomKey, ignoreCase = true) && rule.clarifyingQuestions != null
                    }
                }

                if (hasQuestions) {
                    _state.update { it.copy(navigateToDiagnose = true, isDiagnosed = true) }
                } else {
                    viewModelScope.launch {
                        if (patientId == -1) {
                            _state.update { it.copy(logout = true) }
                            return@launch
                        }
                        if (!_state.value.isDiagnosed) {
                            val diagnoses = symptoms.map { symptom ->
                                diagnoseSymptom(symptom, rules, emptyMap())
                            }
                            val symptomEntity = SymptomEntity(
                                userInput = symptoms.joinToString(". "),
                                medicalTerm = diagnoses.mapNotNull { it.medicalTerm }.joinToString(", "),
                                probability = diagnoses.sumOf { it.probability },
                                patientId = patientId,
                                clarifyingAnswers = null,
                                createdAt = LocalDateTime.now()
                            )
                            db.symptomDao().insert(symptomEntity)
                            _state.update { it.copy(navigateToDiagnose = true, isDiagnosed = true) }
                        } else {
                            _state.update { it.copy(navigateToDiagnose = true) }
                        }
                    }
                }
            }
            is SymptomsScreenIntent.Logout -> {
                _state.update { it.copy(logout = true) }
            }
        }
    }

    fun resetDiagnosedState() {
        _state.update { it.copy(navigateToDiagnose = false, isDiagnosed = false, symptoms = emptyList()) }
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