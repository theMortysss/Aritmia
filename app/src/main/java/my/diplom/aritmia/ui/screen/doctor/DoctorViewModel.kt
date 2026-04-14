package my.diplom.aritmia.ui.screen.doctor

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
import my.diplom.aritmia.ui.screen.doctor.model.DoctorScreenIntent
import my.diplom.aritmia.ui.screen.doctor.model.DoctorScreenState
import my.diplom.aritmia.ui.screen.doctor.model.SymptomItem
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class DoctorScreenViewModel @Inject constructor(
    private val db: AppDatabase
) : ViewModel() {
    private val _state = MutableStateFlow(DoctorScreenState())
    val state: StateFlow<DoctorScreenState> = _state.asStateFlow()

    private val pageSize = 10

    init {
        viewModelScope.launch {
            db.ruleDao().getAllRulesFlow().collect { rules ->
                _state.update { it.copy(rules = rules) }
            }
        }
        loadSymptoms()
    }

    fun onIntent(intent: DoctorScreenIntent) {
        when (intent) {
            is DoctorScreenIntent.ChangePage -> {
                _state.update { it.copy(page = intent.newPage) }
                loadSymptoms()
            }
            is DoctorScreenIntent.ApplyFilters -> {
                val normalizedPhoneFilter = intent.phone.trim().filter { it.isDigit() }
                val normalizedNameFilter = intent.name.trim()
                _state.update {
                    it.copy(
                        phoneFilter = normalizedPhoneFilter,
                        nameFilter = normalizedNameFilter,
                        minProbability = intent.minProbability,
                        startDate = intent.startDate,
                        endDate = intent.endDate,
                        page = 0
                    )
                }
                loadSymptoms()
            }
            is DoctorScreenIntent.UpdateTempFilter -> {
                _state.update {
                    it.copy(
                        tempPhoneFilter = intent.phone?.filter { char -> char.isDigit() } ?: it.tempPhoneFilter,
                        tempNameFilter = intent.name?.trim() ?: it.tempNameFilter,
                        tempMinProbability = intent.minProbability ?: it.tempMinProbability,
                        tempStartDate = intent.startDate ?: it.tempStartDate,
                        tempEndDate = intent.endDate ?: it.tempEndDate
                    )
                }
            }
            is DoctorScreenIntent.ResetFilters -> {
                _state.update {
                    it.copy(
                        tempPhoneFilter = "",
                        tempNameFilter = "",
                        tempMinProbability = 0,
                        tempStartDate = null,
                        tempEndDate = null
                    )
                }
            }
            is DoctorScreenIntent.ShowFilterSheet -> {
                _state.update {
                    it.copy(
                        showFilterSheet = true,
                        tempPhoneFilter = it.phoneFilter,
                        tempNameFilter = it.nameFilter,
                        tempMinProbability = it.minProbability,
                        tempStartDate = it.startDate,
                        tempEndDate = it.endDate
                    )
                }
            }
            is DoctorScreenIntent.HideFilterSheet -> {
                _state.update { it.copy(showFilterSheet = false) }
            }
            is DoctorScreenIntent.ChangeTab -> {
                _state.update { it.copy(selectedTabIndex = intent.tabIndex) }
            }
            is DoctorScreenIntent.ShowRuleEditor -> {
                _state.update { it.copy(showRuleEditor = true) }
            }
            is DoctorScreenIntent.HideRuleEditor -> {
                _state.update { it.copy(showRuleEditor = false) }
            }
            is DoctorScreenIntent.SelectRule -> {
                _state.update { it.copy(selectedRule = intent.rule) }
            }
            is DoctorScreenIntent.SaveRule -> {
                viewModelScope.launch {
                    if (intent.rule.id == 0) {
                        db.ruleDao().insert(intent.rule)
                    } else {
                        db.ruleDao().update(intent.rule)
                    }
                }
            }
            is DoctorScreenIntent.DeleteRule -> {
                viewModelScope.launch {
                    db.ruleDao().delete(intent.rule)
                }
            }
            is DoctorScreenIntent.Logout -> {
                _state.update { it.copy(logout = true) }
            }
            is DoctorScreenIntent.MarkPatientAsCalled -> {
                viewModelScope.launch {
                    db.symptomDao().updateCalledByDoctor(intent.symptomId, intent.called)

                    val updatedSymptoms = _state.value.symptoms.map { symptomItem ->
                        if (symptomItem.symptom.id == intent.symptomId) {
                            symptomItem.copy(
                                symptom = symptomItem.symptom.copy(calledByDoctor = intent.called)
                            )
                        } else {
                            symptomItem
                        }
                    }
                    _state.update { it.copy(symptoms = updatedSymptoms) }
                }
            }
        }
    }

    private fun loadSymptoms() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val offset = _state.value.page * pageSize

            val symptoms = db.symptomDao().getSymptomsFiltered(
                phoneFilter = _state.value.phoneFilter,
                nameFilter = _state.value.nameFilter,
                minProbability = _state.value.minProbability,
                startDate = _state.value.startDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                endDate = _state.value.endDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                limit = pageSize,
                offset = offset
            )

            val totalCount = db.symptomDao().getFilteredCount(
                phoneFilter = _state.value.phoneFilter,
                nameFilter = _state.value.nameFilter,
                minProbability = _state.value.minProbability,
                startDate = _state.value.startDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                endDate = _state.value.endDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )

            val allPatients = db.userDao().getAllPatients()

            val symptomItems = symptoms.map { symptom ->
                val user = allPatients.find { it.id == symptom.patientId }
                val userSymptoms = symptom.userInput.split(". ").filter { it.isNotBlank() }
                val medicalTerms = symptom.medicalTerm?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()

                val recognizedSymptoms = mutableListOf<String>()
                val unrecognizedSymptoms = mutableListOf<String>()
                val recognizedMedicalTerms = mutableListOf<String>()
                val clarifyingAnswers = symptom.clarifyingAnswers?.split(";")?.filter { it.isNotBlank() }
                    ?.associate { entry ->
                        val (symptomKey, answers) = entry.split("=")
                        symptomKey to answers.split(",").filter { it.isNotBlank() }
                    } ?: emptyMap()

                userSymptoms.forEachIndexed { index, userSymptom ->
                    val matchingRule = _state.value.rules.find { rule ->
                        userSymptom.contains(rule.symptomKey, ignoreCase = true)
                    }
                    val medicalTerm = medicalTerms.getOrNull(index)

                    if (matchingRule != null && medicalTerm != null && medicalTerm != "Нераспознанный симптом") {
                        val possibleTerms = mutableListOf(matchingRule.medicalTerm)
                        matchingRule.answerTriggers?.split(";")?.forEach {
                            val term = it.split("=").getOrNull(1)
                            if (term != null) possibleTerms.add(term)
                        }
                        val isRecognized = possibleTerms.contains(medicalTerm)
                        if (isRecognized) {
                            recognizedSymptoms.add(userSymptom)
                            recognizedMedicalTerms.add(medicalTerm)
                        } else {
                            unrecognizedSymptoms.add(userSymptom)
                        }
                    } else {
                        unrecognizedSymptoms.add(userSymptom)
                    }
                }

                SymptomItem(
                    symptom = symptom,
                    user = user,
                    recognizedSymptoms = recognizedSymptoms,
                    unrecognizedSymptoms = unrecognizedSymptoms,
                    recognizedMedicalTerms = recognizedMedicalTerms,
                    clarifyingAnswers = clarifyingAnswers
                )
            }

            _state.update {
                it.copy(
                    totalCount = totalCount,
                    symptoms = symptomItems,
                    isLoading = false
                )
            }
        }
    }
}
