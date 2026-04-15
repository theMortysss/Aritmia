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
import my.diplom.aritmia.nn.NetworkRepository
import my.diplom.aritmia.ui.screen.clarify.model.ClarifyScreenIntent
import my.diplom.aritmia.ui.screen.clarify.model.ClarifyScreenState
import javax.inject.Inject

@HiltViewModel
class ClarifyViewModel @Inject constructor(
    private val db: AppDatabase,
    private val networkRepository: NetworkRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ClarifyScreenState())
    val state: StateFlow<ClarifyScreenState> = _state.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.O)
    fun onIntent(intent: ClarifyScreenIntent) {
        when (intent) {
            is ClarifyScreenIntent.Initialize -> {
                _state.update {
                    it.copy(
                        symptoms  = intent.symptoms,
                        userId    = intent.userId,
                        answers   = intent.initialAnswers.toMutableMap()
                            .mapValues { e -> e.value.toMutableList() },
                        isLoading = true
                    )
                }
                viewModelScope.launch {
                    val rules = db.ruleDao().getAllRules()
                    _state.update { it.copy(rules = rules, isLoading = false) }
                    if (!networkRepository.isReady()) {
                        networkRepository.initialize(rules)
                    }
                }
            }

            is ClarifyScreenIntent.UpdateAnswer -> {
                val cur = _state.value.answers.toMutableMap()
                val ans = cur[intent.symptom]?.toMutableList() ?: mutableListOf()
                while (ans.size <= intent.questionIndex) ans.add("")
                ans[intent.questionIndex] = intent.answer
                cur[intent.symptom] = ans
                _state.update { it.copy(answers = cur) }
            }

            is ClarifyScreenIntent.Finish ->
                _state.update { it.copy(navigateToFinish = true) }

            is ClarifyScreenIntent.Logout ->
                _state.update { it.copy(logout = true) }
        }
    }
}

// ── Вспомогательная функция ─────────────

data class SymptomTermResult(
    val userInput: String,
    val medicalTerm: String?
)

fun resolveSymptomTerm(
    symptom: String,
    rules: List<RuleEntity>,
    answers: Map<String, List<String>>
): SymptomTermResult {
    val rule = rules.find { symptom.contains(it.symptomKey, ignoreCase = true) }
        ?: return SymptomTermResult(symptom, null)

    var medicalTerm = rule.medicalTerm
    rule.clarifyingQuestions
        ?.split(";")?.filter { it.isNotBlank() }
        ?.forEachIndexed { index, _ ->
            val answer = answers[symptom]?.getOrNull(index)
            if (answer != null && answer != "не могу ответить") {
                rule.answerTriggers?.split(";")?.forEach { trigger ->
                    val parts = trigger.split("=")
                    if (parts.size == 2 && answer == parts[0]) medicalTerm = parts[1]
                }
            }
        }

    return SymptomTermResult(symptom, medicalTerm)
}
