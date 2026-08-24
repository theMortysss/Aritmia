package my.diplom.aritmia.diagnosis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Репозиторий многоклассовой сердечно-сосудистой модели.
 *
 * Приоритет: загрузить заранее обученную модель из assets/disease_model.json либо
 * финальные v2-части из assets/disease_model/. Если pretrained asset отсутствует или
 * несовместим, приложение остаётся работоспособным за счёт bootstrap fallback.
 */
class DiseaseNetworkRepository(private val context: Context) {

    companion object {
        private const val MODEL_ASSET = "disease_model.json"
        private const val MODEL_PARTS_DIR = "disease_model"
        private const val MODEL_PART_PREFIX = "v2-"
        private const val MODEL_TYPE = "aritmia_symptom_multiclass_mlp"
        private const val FORMAT_VERSION = 1
    }

    @Volatile private var network: DiseaseNeuralNetwork? = null
    @Volatile private var outputDiseaseIds: List<String> = emptyList()
    @Volatile private var pretrained: Boolean = false

    suspend fun initialize() = withContext(Dispatchers.Default) {
        if (network != null) return@withContext
        synchronized(this@DiseaseNetworkRepository) {
            if (network != null) return@synchronized

            val loaded = loadPretrainedModel()
            if (loaded != null) {
                network = loaded.first
                outputDiseaseIds = loaded.second
                pretrained = true
                return@synchronized
            }

            val definitions = DiseaseCatalog.definitions
            val model = DiseaseNeuralNetwork(
                inputSize = DiseaseCatalog.concepts.size,
                hiddenSize = 36,
                outputSize = definitions.size
            )
            model.train(generateBootstrapSamples())
            network = model
            outputDiseaseIds = definitions.map { it.id }
            pretrained = false
        }
    }

    suspend fun classify(
        complaints: List<String>,
        medicalTerms: List<String> = emptyList(),
        limit: Int = 5
    ): List<DiseaseCandidate> = withContext(Dispatchers.Default) {
        initialize()
        val model = network ?: return@withContext emptyList()
        val outputs = outputDiseaseIds
        if (outputs.size != model.outputSize) return@withContext emptyList()

        val extraction = FreeTextSymptomExtractor.extract(complaints + medicalTerms)
        if (extraction.conceptIds.isEmpty()) return@withContext emptyList()

        val conceptIndex = DiseaseCatalog.concepts
            .mapIndexed { index, concept -> concept.id to index }
            .toMap()
        val vector = DoubleArray(DiseaseCatalog.concepts.size)
        extraction.conceptIds.forEach { id ->
            conceptIndex[id]?.let { index -> vector[index] = 1.0 }
        }

        val probabilities = model.predict(vector)
        outputs.mapIndexedNotNull { index, diseaseId ->
            val disease = DiseaseCatalog.disease(diseaseId) ?: return@mapIndexedNotNull null
            DiseaseCandidate(
                id = disease.id,
                name = disease.name,
                modelScorePercent = (probabilities[index] * 100.0).roundToInt().coerceIn(0, 100),
                matchedSignals = DiseaseCatalog.explain(disease.id, extraction.conceptIds)
            )
        }
            .sortedByDescending { it.modelScorePercent }
            .take(limit.coerceAtLeast(0))
    }

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
        android.util.Log.w("DiseaseNetwork", "Pretrained model unavailable; using bootstrap fallback", error)
        null
    }

    /** Fallback development data only. */
    private fun generateBootstrapSamples(samplesPerDisease: Int = 90): List<DiseaseTrainingSample> {
        val rng = Random(20260824)
        val concepts = DiseaseCatalog.concepts
        val indexByConcept = concepts.mapIndexed { index, concept -> concept.id to index }.toMap()
        val definitions = DiseaseCatalog.definitions
        val allConceptIds = concepts.map { it.id }
        val samples = mutableListOf<DiseaseTrainingSample>()

        definitions.forEachIndexed { labelIndex, disease ->
            val diseaseConcepts = disease.conceptWeights.keys.toList()
            repeat(samplesPerDisease) {
                val vector = DoubleArray(concepts.size)
                val anchors = disease.conceptWeights.entries
                    .sortedByDescending { entry -> entry.value }
                    .take(3)
                    .map { entry -> entry.key }
                anchors.random(rng).let { id -> indexByConcept[id]?.let { vector[it] = 1.0 } }

                disease.conceptWeights.forEach { (id, weight) ->
                    val probability = (0.22 + 0.62 * weight).coerceIn(0.15, 0.9)
                    if (rng.nextDouble() < probability) {
                        indexByConcept[id]?.let { vector[it] = 1.0 }
                    }
                }
                repeat(rng.nextInt(0, 3)) {
                    val noise = allConceptIds.random(rng)
                    if (noise !in diseaseConcepts && rng.nextDouble() < 0.55) {
                        indexByConcept[noise]?.let { vector[it] = 1.0 }
                    }
                }
                samples += DiseaseTrainingSample(vector, labelIndex)
            }
        }
        return samples.shuffled(rng)
    }
}
