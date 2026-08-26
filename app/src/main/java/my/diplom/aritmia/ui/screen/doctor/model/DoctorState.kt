package my.diplom.aritmia.ui.screen.doctor.model

import androidx.compose.runtime.Immutable
import my.diplom.aritmia.data.AssessmentEntity
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.StoredDiseaseCandidate
import my.diplom.aritmia.data.SymptomEntity
import my.diplom.aritmia.data.User
import java.time.LocalDateTime

@Immutable
data class DoctorScreenState(
    val selectedTabIndex: Int = 0,
    val phoneFilter: String = "",
    val nameFilter: String = "",
    val startDate: LocalDateTime? = null,
    val endDate: LocalDateTime? = null,
    val tempPhoneFilter: String = "",
    val tempNameFilter: String = "",
    val tempStartDate: LocalDateTime? = null,
    val tempEndDate: LocalDateTime? = null,
    val showFilterSheet: Boolean = false,
    val statusFilter: String = "ALL",
    val workflowFilter: String = "ALL",
    val attentionOnly: Boolean = false,
    val assessments: List<DoctorAssessmentItem> = emptyList(),
    val selectedAssessment: DoctorAssessmentItem? = null,
    val patientTimeline: List<DoctorAssessmentItem> = emptyList(),
    val showAssessmentDialog: Boolean = false,
    val doctorNoteDraft: String = "",
    // Legacy fields remain for compatibility with existing tests and incremental migration.
    val symptoms: List<SymptomItem> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 0,
    val isLoading: Boolean = false,
    val rules: List<RuleEntity> = emptyList(),
    val showRuleEditor: Boolean = false,
    val selectedRule: RuleEntity? = null,
    val logout: Boolean = false
)

data class DoctorAssessmentItem(
    val assessment: AssessmentEntity,
    val user: User?,
    val conceptLabels: List<String>,
    val candidates: List<StoredDiseaseCandidate>
)

data class SymptomItem(
    val symptom: SymptomEntity,
    val user: User?,
    val recognizedSymptoms: List<String>,
    val unrecognizedSymptoms: List<String>,
    val recognizedMedicalTerms: List<String>,
    val clarifyingAnswers: Map<String, List<String>>
)
