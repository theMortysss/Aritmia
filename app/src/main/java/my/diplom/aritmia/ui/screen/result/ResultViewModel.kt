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
import my.diplom.aritmia.data.AssessmentEntity
import my.diplom.aritmia.data.AssessmentSnapshotCodec
import my.diplom.aritmia.data.AssessmentWorkflow
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.SymptomEntity
import my.diplom.aritmia.diagnosis.ComplaintOntology
import my.diplom.aritmia.diagnosis.ComplaintTriage
import my.diplom.aritmia.diagnosis.ComplaintTriageAssessment
import my.diplom.aritmia.diagnosis.ComplaintTriageLevel
import my.diplom.aritmia.diagnosis.DiseaseAssessment
import my.diplom.aritmia.diagnosis.DiseaseAssessmentStatus
import my.diplom.aritmia.diagnosis.DiseaseNetworkRepository
import my.diplom.aritmia.diagnosis.FreeTextSymptomExtractor
import my.diplom.aritmia.ui.screen.SharedViewModel
import my.diplom.aritmia.ui.screen.clarify.hasClarificationQuestions
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
                    val ontologySuggestions = ComplaintOntology.suggestions(intent.editedSymptom, limit = 12)
                    val ruleSuggestions = _state.value.rules.filter {
                        it.symptomKey.contains(intent.editedSymptom, ignoreCase = true) ||
                            it.medicalTerm.contains(intent.editedSymptom, ignoreCase = true)
                    }.map { it.symptomKey }.distinct()
                    (ontologySuggestions + ruleSuggestions).distinct().take(12)
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
            val diagnosis = db.symptomDao().getAllSymptoms().lastOrNull { it.patientId == userId }
            val rules = db.ruleDao().getAllRules()

            if (diagnosis != null) {
                val (recognized, unrecognized, recTerms) =
                    classifySymptoms(diagnosis.userInput, diagnosis.medicalTerm, rules)

                val userComplaints = diagnosis.userInput.split(". ").filter { it.isNotBlank() }
                val storedAnswers = parseAnswers(diagnosis.clarifyingAnswers)
                val triage = ComplaintTriage.assess(
                    complaints = userComplaints,
                    clarificationAnswers = triageAnswersFor(userComplaints, rules, storedAnswers)
                )
                val assessment = diseaseNetworkRepository.assess(
                    complaints = userComplaints,
                    limit = 5
                )
                persistAssessment(diagnosis, assessment, triage)

                _state.update {
                    it.copy(
                        diagnosis = diagnosis,
                        rules = rules,
                        recognizedSymptoms = recognized,
                        unrecognizedSymptoms = unrecognized,
                        recognizedMedicalTerms = recTerms,
                        triageAssessment = triage,
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

            val updatedDiagnosis = diagnosis.copy(
                userInput = updatedInput,
                medicalTerm = newMedTerms.ifBlank { null },
                clarifyingAnswers = answers.entries
                    .filter { it.value.any { a -> a.isNotBlank() } }
                    .joinToString(";") { "${it.key}=${it.value.joinToString(",")}" },
                nnProbability = null
            )
            db.symptomDao().update(updatedDiagnosis)

            if (hasClarificationQuestions(listOf(edited), rules)) {
                sharedViewModel.setData(updatedSymptoms, diagnosis.patientId, answers)
                _state.update { it.copy(navigateToClarify = true, showDialog = false) }
            } else {
                val (recognized, unrecognized, recTerms) =
                    classifySymptoms(updatedDiagnosis.userInput, updatedDiagnosis.medicalTerm, rules)
                val triage = ComplaintTriage.assess(
                    complaints = updatedSymptoms,
                    clarificationAnswers = triageAnswersFor(updatedSymptoms, rules, answers)
                )
                val assessment = diseaseNetworkRepository.assess(
                    complaints = updatedSymptoms,
                    limit = 5
                )
                persistAssessment(updatedDiagnosis, assessment, triage)
                _state.update {
                    it.copy(
                        diagnosis = updatedDiagnosis,
                        recognizedSymptoms = recognized,
                        unrecognizedSymptoms = unrecognized,
                        recognizedMedicalTerms = recTerms,
                        triageAssessment = triage,
                        diseaseAssessmentStatus = assessment.status,
                        recognizedDiseaseConceptCount = assessment.recognizedConceptIds.size,
                        diseaseCandidates = assessment.candidates,
                        showDialog = false
                    )
                }
            }
        }
    }

    private suspend fun persistAssessment(
        diagnosis: SymptomEntity,
        assessment: DiseaseAssessment,
        triage: ComplaintTriageAssessment
    ) {
        val existing = db.assessmentDao().getBySourceSymptomId(diagnosis.id)
        val defaultNeedsAttention =
            assessment.status != DiseaseAssessmentStatus.RANKED || triage.level != ComplaintTriageLevel.NONE
        val preserveDoctorWorkflow = existing != null && existing.workflowStatus != AssessmentWorkflow.NEW

        val snapshot = AssessmentEntity(
            id = existing?.id ?: 0,
            sourceSymptomId = diagnosis.id,
            patientId = diagnosis.patientId,
            complaints = diagnosis.userInput,
            status = assessment.status.name,
            recognizedConceptIds = AssessmentSnapshotCodec.encodeConceptIds(assessment.recognizedConceptIds),
            modelCandidates = AssessmentSnapshotCodec.encodeCandidates(assessment.candidates),
            modelVersion = DiseaseNetworkRepository.MODEL_VERSION,
            extractorVersion = DiseaseNetworkRepository.EXTRACTOR_VERSION,
            createdAt = existing?.createdAt ?: diagnosis.createdAt,
            workflowStatus = existing?.workflowStatus ?: AssessmentWorkflow.NEW,
            needsDoctorAttention = if (preserveDoctorWorkflow) {
                existing?.needsDoctorAttention ?: defaultNeedsAttention
            } else {
                defaultNeedsAttention
            },
            doctorNote = existing?.doctorNote
        )

        if (existing == null) db.assessmentDao().insert(snapshot)
        else db.assessmentDao().update(snapshot)
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
                val ontologyLabels = FreeTextSymptomExtractor.extract(listOf(s)).conceptIds
                    .mapNotNull { ComplaintOntology.concept(it)?.label }
                    .distinct()
                if (ontologyLabels.isNotEmpty()) {
                    recognized.add(s)
                    recTerms.addAll(ontologyLabels)
                } else {
                    unrecognized.add(s)
                }
            }
        }
        return SymptomClassification(recognized, unrecognized, recTerms.distinct())
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
