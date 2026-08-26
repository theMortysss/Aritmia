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
import my.diplom.aritmia.ui.screen.doctor.model.resolveDateFilterUpdate
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
                _state.update {
                    it.copy(
                        phoneFilter = intent.phone.trim().filter { c -> c.isDigit() },
                        nameFilter = intent.name.trim(),
                        startDate = intent.startDate,
                        endDate = intent.endDate,
                        page = 0
                    )
                }
                loadSymptoms()
            }

            is DoctorScreenIntent.UpdateTempFilter ->
                _state.update {
                    it.copy(
                        tempPhoneFilter = intent.phone?.filter { c -> c.isDigit() } ?: it.tempPhoneFilter,
                        tempNameFilter = intent.name?.trim() ?: it.tempNameFilter,
                        tempStartDate = resolveDateFilterUpdate(it.tempStartDate, intent.startDate),
                        tempEndDate = resolveDateFilterUpdate(it.tempEndDate, intent.endDate)
                    )
                }

            is DoctorScreenIntent.ResetFilters ->
                _state.update {
                    it.copy(
                        tempPhoneFilter = "",
                        tempNameFilter = "",
                        tempStartDate = null,
                        tempEndDate = null
                    )
                }

            is DoctorScreenIntent.ShowFilterSheet ->
                _state.update {
                    it.copy(
                        showFilterSheet = true,
                        tempPhoneFilter = it.phoneFilter,
                        tempNameFilter = it.nameFilter,
                        tempStartDate = it.startDate,
                        tempEndDate = it.endDate
                    )
                }

            is DoctorScreenIntent.HideFilterSheet ->
                _state.update { it.copy(showFilterSheet = false) }

            is DoctorScreenIntent.ChangeTab ->
                _state.update { it.copy(selectedTabIndex = intent.tabIndex) }

            is DoctorScreenIntent.ShowRuleEditor ->
                _state.update { it.copy(showRuleEditor = true) }

            is DoctorScreenIntent.HideRuleEditor ->
                _state.update { it.copy(showRuleEditor = false) }

            is DoctorScreenIntent.SelectRule ->
                _state.update { it.copy(selectedRule = intent.rule) }

            is DoctorScreenIntent.SaveRule -> {
                viewModelScope.launch {
                    if (intent.rule.id == 0) db.ruleDao().insert(intent.rule)
                    else db.ruleDao().update(intent.rule)
                }
            }

            is DoctorScreenIntent.DeleteRule -> {
                viewModelScope.launch { db.ruleDao().delete(intent.rule) }
            }

            is DoctorScreenIntent.Logout ->
                _state.update { it.copy(logout = true) }

            is DoctorScreenIntent.MarkPatientAsCalled -> {
                viewModelScope.launch {
                    db.symptomDao().updateCalledByDoctor(intent.symptomId, intent.called)
                    _state.update {
                        it.copy(symptoms = it.symptoms.map { item ->
                            if (item.symptom.id == intent.symptomId) {
                                item.copy(symptom = item.symptom.copy(calledByDoctor = intent.called))
                            } else {
                                item
                            }
                        })
                    }
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
                minProbability = 0,
                startDate = _state.value.startDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                endDate = _state.value.endDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                limit = pageSize,
                offset = offset
            )

            val totalCount = db.symptomDao().getFilteredCount(
                phoneFilter = _state.value.phoneFilter,
                nameFilter = _state.value.nameFilter,
                minProbability = 0,
                startDate = _state.value.startDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                endDate = _state.value.endDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )

            val allPatients = db.userDao().getAllPatients()
            val rules = _state.value.rules

            val items = symptoms.map { symptom ->
                val user = allPatients.find { it.id == symptom.patientId }
                val userSymptoms = symptom.userInput.split(". ").filter { it.isNotBlank() }
                val medTerms = symptom.medicalTerm?.split(", ")?.filter { it.isNotBlank() }
                    ?: emptyList()

                val recognized = mutableListOf<String>()
                val unrecognized = mutableListOf<String>()
                val recTerms = mutableListOf<String>()
                val clarAnswers = symptom.clarifyingAnswers?.split(";")
                    ?.filter { it.isNotBlank() }
                    ?.associate { e ->
                        val (k, v) = e.split("=")
                        k to v.split(",").filter { it.isNotBlank() }
                    } ?: emptyMap()

                userSymptoms.forEachIndexed { idx, s ->
                    val match = rules.find { s.contains(it.symptomKey, ignoreCase = true) }
                    val term = medTerms.getOrNull(idx)
                    if (match != null && term != null && term != "Нераспознанный симптом") {
                        val possible = buildList {
                            add(match.medicalTerm)
                            match.answerTriggers?.split(";")?.forEach { t ->
                                t.split("=").getOrNull(1)?.let { add(it) }
                            }
                        }
                        if (possible.contains(term)) {
                            recognized.add(s)
                            recTerms.add(term)
                        } else {
                            unrecognized.add(s)
                        }
                    } else {
                        unrecognized.add(s)
                    }
                }

                SymptomItem(
                    symptom = symptom,
                    user = user,
                    recognizedSymptoms = recognized,
                    unrecognizedSymptoms = unrecognized,
                    recognizedMedicalTerms = recTerms,
                    clarifyingAnswers = clarAnswers
                )
            }

            _state.update {
                it.copy(totalCount = totalCount, symptoms = items, isLoading = false)
            }
        }
    }
}
