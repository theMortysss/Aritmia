package my.diplom.aritmia.diagnosis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

enum class DiseaseAssessmentStatus {
    OUT_OF_SCOPE,
    INSUFFICIENT_EVIDENCE,
    MODEL_UNAVAILABLE,
    RANKED
}

data class DiseaseAssessment(
    val status: DiseaseAssessmentStatus,
    val recognizedConceptIds: Set<String>,
    val candidates: List<DiseaseCandidate> = emptyList()
)

/**
 * Репозиторий многоклассовой сердечно-сосудистой модели.
 *
 * Единственный вход модели — свободно введённые жалобы пациента. Производные
 * медицинские термины, демография, ЭКГ, лабораторные показатели и другие
 * структурированные данные в классификатор заболеваний не подаются.
 */
class DiseaseNetworkRepository(private val context: Context) {

    companion object {
        private const val MODEL_ASSET = "disease_model.json"
        private const val MODEL_PARTS_DIR = "disease_model"
        private const val MODEL_PART_PREFIX = "v2-"
        private const val MODEL_TYPE = "aritmia_symptom_multiclass_mlp"
        private const val FORMAT_VERSION = 1

        const val MODEL_VERSION = "v2"
        const val EXTRACTOR_VERSION = "russian-complaint-v4"
        const val MIN_CONCEPTS_FOR_RANKING = 2 // 4?
    }

    @Volatile private var network: DiseaseNeuralNetwork? = null
    @Volatile private var outputDiseaseIds: List<String> = emptyList()
    @Volatile private var pretrained: Boolean = false
    @Volatile private var initializationAttempted: Boolean = false

    suspend fun initialize() = withContext(Dispatchers.Default) {
        if (initializationAttempted) return@withContext
        synchronized(this@DiseaseNetworkRepository) {
            if (initializationAttempted) return@synchronized

            val loaded = loadPretrainedModel()
            if (loaded != null) {
                network = loaded.first
                outputDiseaseIds = loaded.second
                pretrained = true
            } else {
                network = null
                outputDiseaseIds = emptyList()
                pretrained = false
            }
            initializationAttempted = true
        }
    }

    suspend fun assess(
        complaints: List<String>,
        limit: Int = 5
    ): DiseaseAssessment = withContext(Dispatchers.Default) {
        val extraction = FreeTextSymptomExtractor.extract(complaints)
        val modelConcepts = extraction.modelConceptIds

        // OUT_OF_SCOPE now means the complaint ontology recognized nothing at all.
        // A known context-only complaint (for example low_bp) remains in-scope for the
        // dialogue/clarification layer even though it is not an MLP v2 feature.
        if (extraction.conceptIds.isEmpty()) {
            return@withContext DiseaseAssessment(
                status = DiseaseAssessmentStatus.OUT_OF_SCOPE,
                recognizedConceptIds = emptySet()
            )
        }

        if (modelConcepts.size < MIN_CONCEPTS_FOR_RANKING) {
            return@withContext DiseaseAssessment(
                status = DiseaseAssessmentStatus.INSUFFICIENT_EVIDENCE,
                recognizedConceptIds = modelConcepts
            )
        }

        initialize()
        val model = network ?: return@withContext DiseaseAssessment(
            status = DiseaseAssessmentStatus.MODEL_UNAVAILABLE,
            recognizedConceptIds = modelConcepts
        )
        val outputs = outputDiseaseIds
        if (outputs.size != model.outputSize) {
            return@withContext DiseaseAssessment(
                status = DiseaseAssessmentStatus.MODEL_UNAVAILABLE,
                recognizedConceptIds = modelConcepts
            )
        }

        val conceptIndex = DiseaseCatalog.concepts
            .mapIndexed { index, concept -> concept.id to index }
            .toMap()
        val vector = DoubleArray(DiseaseCatalog.concepts.size)
        modelConcepts.forEach { id -> conceptIndex[id]?.let { vector[it] = 1.0 } }

        val probabilities = model.predict(vector)
        val candidates = outputs.mapIndexedNotNull { index, diseaseId ->
            val disease = DiseaseCatalog.disease(diseaseId) ?: return@mapIndexedNotNull null
            DiseaseCandidate(
                id = disease.id,
                name = disease.name,
                modelScorePercent = (probabilities[index] * 100.0).roundToInt().coerceIn(0, 100),
                matchedSignals = DiseaseCatalog.explain(disease.id, modelConcepts)
            )
        }
            .sortedByDescending { it.modelScorePercent }
            .take(limit.coerceAtLeast(0))

        DiseaseAssessment(
            status = DiseaseAssessmentStatus.RANKED,
            recognizedConceptIds = modelConcepts,
            candidates = candidates
        )
    }

    suspend fun classify(
        complaints: List<String>,
        limit: Int = 5
    ): List<DiseaseCandidate> = assess(complaints, limit).candidates

    fun isReady(): Boolean = network != null
    fun isUsingPretrainedModel(): Boolean = pretrained
    fun lastLoss(): Double? = network?.lastLoss
    fun lastEpochs(): Int? = network?.lastEpochs

    private fun readModelJson(): String {
        runCatching {
            return context.assets.open(MODEL_ASSET).bufferedReader().use { it.readText() }
        }

        val parts = context.assets.list(MODEL_PARTS_DIR)
            ?.filter { it.startsWith(MODEL_PART_PREFIX) && it.endsWith(".part") }
            ?.sorted()
            .orEmpty()
        require(parts.isNotEmpty()) { "Pretrained disease model asset not found" }

        return buildString {
            parts.forEach { fileName ->
                append(
                    context.assets.open("$MODEL_PARTS_DIR/$fileName")
                        .bufferedReader()
                        .use { it.readText() }
                )
            }
        }
    }

    private fun loadPretrainedModel(): Pair<DiseaseNeuralNetwork, List<String>>? = runCatching {
        val raw = readModelJson()
        val json = Json { ignoreUnknownKeys = true }
        val snapshot = json.decodeFromString<DiseaseModelSnapshot>(raw)

        require(snapshot.formatVersion == FORMAT_VERSION)
        require(snapshot.modelType == MODEL_TYPE)
        require(snapshot.activation.equals("relu", ignoreCase = true))
        require(snapshot.outputActivation.equals("softmax", ignoreCase = true))

        val expectedInputs = DiseaseCatalog.concepts.map { it.id }
        require(snapshot.inputConceptIds == expectedInputs) {
            "Несовместимый порядок symptom-concepts в pretrained disease model"
        }
        require(snapshot.outputDiseaseIds.isNotEmpty())
        require(snapshot.outputDiseaseIds.distinct().size == snapshot.outputDiseaseIds.size)
        require(snapshot.outputDiseaseIds.all { DiseaseCatalog.disease(it) != null })

        val model = DiseaseNeuralNetwork(
            inputSize = snapshot.inputConceptIds.size,
            hiddenSize = snapshot.hiddenSize,
            outputSize = snapshot.outputDiseaseIds.size
        )
        model.loadWeights(snapshot)
        model to snapshot.outputDiseaseIds
    }.getOrElse { error ->
        android.util.Log.e(
            "DiseaseNetwork",
            "Pretrained disease model unavailable; classification disabled",
            error
        )
        null
    }
}
