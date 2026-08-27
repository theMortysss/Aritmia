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
import my.diplom.aritmia.data.AssessmentEntity
import my.diplom.aritmia.data.AssessmentSnapshotCodec
import my.diplom.aritmia.data.AssessmentWorkflow
import my.diplom.aritmia.data.User
import my.diplom.aritmia.diagnosis.DiseaseCatalog
import my.diplom.aritmia.ui.screen.doctor.model.DoctorAssessmentItem
import my.diplom.aritmia.ui.screen.doctor.model.DoctorScreenIntent
import my.diplom.aritmia.ui.screen.doctor.model.DoctorScreenState
import my.diplom.aritmia.ui.screen.doctor.model.resolveDateFilterUpdate
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class DoctorScreenViewModel @Inject constructor(
    private val db: AppDatabase
) : ViewModel() {

    private val _state = MutableStateFlow(DoctorScreenState())
    val state: StateFlow<DoctorScreenState> = _state.asStateFlow()

    private var allAssessments: List<AssessmentEntity> = emptyList()

    init {
        viewModelScope.launch {
            db.ruleDao().getAllRulesFlow().collect { rules ->
                _state.update { it.copy(rules = rules) }
            }
        }
        viewModelScope.launch {
            db.assessmentDao().observeAll().collect { rows ->
                allAssessments = rows
                renderAssessments()
            }
        }
    }

    fun onIntent(intent: DoctorScreenIntent) {
        when (intent) {
            is DoctorScreenIntent.ChangePage ->
                _state.update { it.copy(page = intent.newPage) }

            is DoctorScreenIntent.ApplyFilters -> {
                _state.update {
                    it.copy(
                        phoneFilter = intent.phone.trim().filter(Char::isDigit),
                        nameFilter = intent.name.trim(),
                        startDate = intent.startDate,
                        endDate = intent.endDate,
                        page = 0
                    )
                }
                refreshRenderedAssessments()
            }

            is DoctorScreenIntent.UpdateTempFilter ->
                _state.update {
                    it.copy(
                        tempPhoneFilter = intent.phone?.filter(Char::isDigit) ?: it.tempPhoneFilter,
                        tempNameFilter = intent.name?.trim() ?: it.tempNameFilter,
                        tempStartDate = resolveDateFilterUpdate(it.tempStartDate, intent.startDate),
                        tempEndDate = resolveDateFilterUpdate(it.tempEndDate, intent.endDate)
                    )
                }

            is DoctorScreenIntent.ResetFilters -> {
                _state.update {
                    it.copy(
                        phoneFilter = "",
                        nameFilter = "",
                        startDate = null,
                        endDate = null,
                        tempPhoneFilter = "",
                        tempNameFilter = "",
                        tempStartDate = null,
                        tempEndDate = null,
                        statusFilter = "ALL",
                        workflowFilter = "ALL",
                        attentionOnly = false,
                        page = 0
                    )
                }
                refreshRenderedAssessments()
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

            is DoctorScreenIntent.SetStatusFilter -> {
                _state.update { it.copy(statusFilter = intent.status) }
                refreshRenderedAssessments()
            }

            is DoctorScreenIntent.SetWorkflowFilter -> {
                _state.update { it.copy(workflowFilter = intent.status) }
                refreshRenderedAssessments()
            }

            is DoctorScreenIntent.SetAttentionOnly -> {
                _state.update { it.copy(attentionOnly = intent.enabled) }
                refreshRenderedAssessments()
            }

            is DoctorScreenIntent.OpenAssessment -> openAssessment(intent.assessmentId)
            is DoctorScreenIntent.CloseAssessment ->
                _state.update {
                    it.copy(
                        selectedAssessment = null,
                        patientTimeline = emptyList(),
                        showAssessmentDialog = false,
                        doctorNoteDraft = ""
                    )
                }

            is DoctorScreenIntent.UpdateDoctorNote ->
                _state.update { it.copy(doctorNoteDraft = intent.note) }

            is DoctorScreenIntent.SaveAssessmentWorkflow ->
                saveAssessmentWorkflow(intent.workflowStatus)

            is DoctorScreenIntent.ShowRuleEditor ->
                _state.update { it.copy(showRuleEditor = true) }

            is DoctorScreenIntent.HideRuleEditor ->
                _state.update { it.copy(showRuleEditor = false) }

            is DoctorScreenIntent.SelectRule ->
                _state.update { it.copy(selectedRule = intent.rule) }

            is DoctorScreenIntent.SaveRule -> viewModelScope.launch {
                if (intent.rule.id == 0) db.ruleDao().insert(intent.rule)
                else db.ruleDao().update(intent.rule)
            }

            is DoctorScreenIntent.DeleteRule -> viewModelScope.launch {
                db.ruleDao().delete(intent.rule)
            }

            is DoctorScreenIntent.Logout ->
                _state.update { it.copy(logout = true) }

            is DoctorScreenIntent.MarkPatientAsCalled -> viewModelScope.launch {
                db.symptomDao().updateCalledByDoctor(intent.symptomId, intent.called)
            }
        }
    }

    private fun refreshRenderedAssessments() {
        viewModelScope.launch { renderAssessments() }
    }

    private suspend fun renderAssessments() {
        _state.update { it.copy(isLoading = true) }
        val patients = db.userDao().getAllPatients().associateBy { it.id }
        val current = _state.value

        val filtered = allAssessments
            .asSequence()
            .filter { assessment ->
                val user = patients[assessment.patientId]
                val phoneMatches = current.phoneFilter.isBlank() ||
                    user?.phone.orEmpty().filter(Char::isDigit).takeLast(10)
                        .contains(current.phoneFilter)
                val nameMatches = current.nameFilter.isBlank() ||
                    user?.fullName.orEmpty().contains(current.nameFilter, ignoreCase = true)
                val startMatches = current.startDate == null || assessment.createdAt >= current.startDate
                val endMatches = current.endDate == null || assessment.createdAt <= current.endDate
                val assessmentStatusMatches = current.statusFilter == "ALL" ||
                    assessment.status == current.statusFilter
                val workflowMatches = current.workflowFilter == "ALL" ||
                    assessment.workflowStatus == current.workflowFilter
                val attentionMatches = !current.attentionOnly || assessment.needsDoctorAttention

                phoneMatches && nameMatches && startMatches && endMatches &&
                    assessmentStatusMatches && workflowMatches && attentionMatches
            }
            .sortedWith(
                compareByDescending<AssessmentEntity> { it.needsDoctorAttention }
                    .thenByDescending { it.createdAt }
            )
            .map { assessment -> buildItem(assessment, patients[assessment.patientId]) }
            .toList()

        // Rendering can suspend while querying patients. During that suspension the doctor may
        // open an assessment. Derive dialog selection from the latest state inside the atomic
        // update so a stale render cannot clear a newly opened assessment before a workflow
        // action is saved.
        _state.update { latest ->
            val selectedId = latest.selectedAssessment?.assessment?.id
            val selected = selectedId?.let { id ->
                filtered.firstOrNull { it.assessment.id == id }
                    ?: allAssessments.firstOrNull { it.id == id }
                        ?.let { buildItem(it, patients[it.patientId]) }
            }
            val timeline = selected?.assessment?.patientId?.let { patientId ->
                allAssessments
                    .filter { it.patientId == patientId }
                    .sortedByDescending { it.createdAt }
                    .map { buildItem(it, patients[patientId]) }
            }.orEmpty()

            latest.copy(
                assessments = filtered,
                totalCount = filtered.size,
                selectedAssessment = selected,
                patientTimeline = timeline,
                isLoading = false
            )
        }
    }

    private fun buildItem(assessment: AssessmentEntity, user: User?): DoctorAssessmentItem {
        val conceptLabels = AssessmentSnapshotCodec.decodeConceptIds(assessment.recognizedConceptIds)
            .map { id -> DiseaseCatalog.concepts.firstOrNull { it.id == id }?.label ?: id }
        return DoctorAssessmentItem(
            assessment = assessment,
            user = user,
            conceptLabels = conceptLabels,
            candidates = AssessmentSnapshotCodec.decodeCandidates(assessment.modelCandidates)
        )
    }

    private fun openAssessment(assessmentId: Int) {
        viewModelScope.launch {
            val row = allAssessments.firstOrNull { it.id == assessmentId }
                ?: db.assessmentDao().getById(assessmentId)
                ?: return@launch
            val patient = db.userDao().getPatientById(row.patientId)
            val item = buildItem(row, patient)
            val timeline = db.assessmentDao().getByPatientId(row.patientId)
                .map { buildItem(it, patient) }
            _state.update {
                it.copy(
                    selectedAssessment = item,
                    patientTimeline = timeline,
                    doctorNoteDraft = row.doctorNote.orEmpty(),
                    showAssessmentDialog = true
                )
            }
        }
    }

    private fun saveAssessmentWorkflow(workflowStatus: String) {
        if (workflowStatus !in AssessmentWorkflow.values) return
        val selected = _state.value.selectedAssessment ?: return
        val note = _state.value.doctorNoteDraft.trim().takeIf { it.isNotBlank() }
        val needsAttention = workflowStatus == AssessmentWorkflow.NEW ||
            workflowStatus == AssessmentWorkflow.CONTACT_REQUIRED

        viewModelScope.launch {
            db.assessmentDao().updateWorkflow(
                assessmentId = selected.assessment.id,
                workflowStatus = workflowStatus,
                doctorNote = note,
                needsDoctorAttention = needsAttention
            )
        }
    }
}
