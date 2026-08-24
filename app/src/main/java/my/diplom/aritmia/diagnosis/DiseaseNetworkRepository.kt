package my.diplom.aritmia.diagnosis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Репозиторий многоклассовой сердечно-сосудистой модели.
 *
 * В текущей мобильной версии сеть bootstrap-обучается на профилях симптом-концептов.
 * Формат входа/выхода специально совпадает с symptom->disease матрицами: внешний
 * размеченный датасет можно импортировать в DiseaseTrainingSample без изменений UI.
 */
class DiseaseNetworkRepository {

    @Volatile private var network: DiseaseNeuralNetwork? = null

    suspend fun initialize() = withContext(Dispatchers.Default) {
        if (network != null) return@withContext
        synchronized(this@DiseaseNetworkRepository) {
            if (network != null) return@synchronized
            val definitions = DiseaseCatalog.definitions
            val model = DiseaseNeuralNetwork(
                inputSize = DiseaseCatalog.concepts.size,
                hiddenSize = 36,
                outputSize = definitions.size
            )
            model.train(generateBootstrapSamples())
            network = model
        }
    }

    suspend fun classify(
        complaints: List<String>,
        medicalTerms: List<String> = emptyList(),
        limit: Int = 5
    ): List<DiseaseCandidate> = withContext(Dispatchers.Default) {
        initialize()
        val model = network ?: return@withContext emptyList()
        val inputs = complaints + medicalTerms
        val extraction = FreeTextSymptomExtractor.extract(inputs)
        if (extraction.conceptIds.isEmpty()) return@withContext emptyList()

        val conceptIndex = DiseaseCatalog.concepts.mapIndexed { index, concept -> concept.id to index }.toMap()
        val vector = DoubleArray(DiseaseCatalog.concepts.size)
        extraction.conceptIds.forEach { conceptIndex[it]?.let { index -> vector[index] = 1.0 } }

        val probabilities = model.predict(vector)
        DiseaseCatalog.definitions
            .mapIndexed { index, disease ->
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
    fun lastLoss(): Double? = network?.lastLoss
    fun lastEpochs(): Int? = network?.lastEpochs

    /**
     * Bootstrap-набор нужен, чтобы приложение работало офлайн сразу после установки.
     * Он генерирует вариативные наборы признаков каждого класса и добавляет шумовые
     * симптомы. В production/исследовательской версии этот метод следует дополнить
     * импортом строк из DDXPlus / symptom-disease datasets.
     */
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

                // Как минимум один из наиболее характерных признаков.
                val anchors = disease.conceptWeights.entries
                    .sortedByDescending { entry -> entry.value }
                    .take(3)
                    .map { entry -> entry.key }
                anchors.random(rng).let { id -> indexByConcept[id]?.let { vector[it] = 1.0 } }

                disease.conceptWeights.forEach { (id, weight) ->
                    val probability = (0.22 + 0.62 * weight).coerceIn(0.15, 0.9)
                    if (rng.nextDouble() < probability) indexByConcept[id]?.let { vector[it] = 1.0 }
                }

                // 0-2 неспецифических/чужих признака имитируют реальную жалобу.
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
