package my.diplom.aritmia.ui.screen.result.model

import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.SymptomEntity
import my.diplom.aritmia.diagnosis.DiseaseAssessmentStatus
import my.diplom.aritmia.diagnosis.DiseaseCandidate

data class ResultScreenState(
    val logout: Boolean = false,
    val diagnosis: SymptomEntity? = null,
    val rules: List<RuleEntity> = emptyList(),
    val isLoading: Boolean = true,
    val showDialog: Boolean = false,
    val selectedSymptom: String? = null,
    val editedSymptom: String = "",
    val recognizedSymptoms: List<String> = emptyList(),
    val unrecognizedSymptoms: List<String> = emptyList(),
    val recognizedMedicalTerms: List<String> = emptyList(),
    val diseaseAssessmentStatus: DiseaseAssessmentStatus = DiseaseAssessmentStatus.OUT_OF_SCOPE,
    val recognizedDiseaseConceptCount: Int = 0,
    val diseaseCandidates: List<DiseaseCandidate> = emptyList(),
    val navigateToClarify: Boolean = false,
    val navigateBack: Boolean = false,
    val suggestions: List<String> = emptyList()
)
