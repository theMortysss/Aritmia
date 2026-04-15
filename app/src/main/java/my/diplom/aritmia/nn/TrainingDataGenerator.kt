package my.diplom.aritmia.nn

import my.diplom.aritmia.data.RuleEntity
import kotlin.math.min
import kotlin.random.Random

class TrainingDataGenerator(private val rules: List<RuleEntity>) {

    val symptomIndex: Map<String, Int> = rules
        .mapIndexed { idx, rule -> rule.symptomKey to idx }
        .toMap()

    val inputSize: Int get() = rules.size

    private val aritmiaIndices = rules.indices.filter { rules[it].probabilityWeight > 0 }
    private val nonAritmiaIndices = rules.indices.filter { rules[it].probabilityWeight == 0 }

    private val realisticMaxWeight: Double = run {
        val topWeights = aritmiaIndices
            .map { rules[it].probabilityWeight }
            .sortedDescending()
            .take(6)
        topWeights.sum().toDouble().coerceAtLeast(1.0)
    }

    fun buildInputVector(symptoms: List<String>): DoubleArray {
        val vec = DoubleArray(inputSize)
        for (symptom in symptoms) {
            for ((key, idx) in symptomIndex) {
                if (symptom.contains(key, ignoreCase = true)) {
                    vec[idx] = 1.0
                    break
                }
            }
        }
        return vec
    }

    fun computeTargetFromVector(vec: DoubleArray): Double {
        var weightSum = 0.0
        for (i in vec.indices) {
            if (vec[i] > 0.5) weightSum += rules[i].probabilityWeight
        }
        return (weightSum / realisticMaxWeight).coerceIn(0.0, 1.0)
    }

    // ── Генерация синтетической обучающей выборки ─────────────────────────────
    fun generateSamples(totalSamples: Int = 1000, seed: Long = 42L): List<TrainingSample> {
        val rng = Random(seed)
        val samples = mutableListOf<TrainingSample>()
        val quarter = totalSamples / 4

        // 1) Явная аритмия — 3–6 тяжёлых симптомов
        repeat(quarter) {
            val vec = DoubleArray(inputSize)
            val count = rng.nextInt(3, min(7, aritmiaIndices.size + 1))
            aritmiaIndices.shuffled(rng).take(count).forEach { idx -> vec[idx] = 1.0 }
            samples.add(TrainingSample(vec, computeTargetFromVector(vec)))
        }

        // 2) Умеренная аритмия — 1–2 аритмических симптома
        repeat(quarter) {
            val vec = DoubleArray(inputSize)
            val count = rng.nextInt(1, min(3, aritmiaIndices.size + 1))
            aritmiaIndices.shuffled(rng).take(count).forEach { idx -> vec[idx] = 1.0 }
            samples.add(TrainingSample(vec, computeTargetFromVector(vec)))
        }

        // 3) Нет аритмии — только неаритмические симптомы (target всегда 0.0)
        repeat(quarter) {
            val vec = DoubleArray(inputSize)
            if (nonAritmiaIndices.isNotEmpty()) {
                val count = rng.nextInt(1, min(5, nonAritmiaIndices.size + 1))
                nonAritmiaIndices.shuffled(rng).take(count).forEach { idx -> vec[idx] = 1.0 }
            }
            // computeTarget вернёт 0.0 т.к. weight=0 у всех неаритмических
            samples.add(TrainingSample(vec, computeTargetFromVector(vec)))
        }

        // 4) Смешанные — реалистичный сценарий
        repeat(totalSamples - 3 * quarter) {
            val vec = DoubleArray(inputSize)
            // Берём 1–3 аритмических + 0–2 неаритмических
            val arCount = rng.nextInt(1, min(4, aritmiaIndices.size + 1))
            aritmiaIndices.shuffled(rng).take(arCount).forEach { idx -> vec[idx] = 1.0 }
            if (nonAritmiaIndices.isNotEmpty() && rng.nextBoolean()) {
                val nonArCount = rng.nextInt(1, min(3, nonAritmiaIndices.size + 1))
                nonAritmiaIndices.shuffled(rng).take(nonArCount).forEach { idx -> vec[idx] = 1.0 }
            }
            samples.add(TrainingSample(vec, computeTargetFromVector(vec)))
        }

        return samples.shuffled(rng)
    }

    fun appendRealSamples(
        existing: MutableList<TrainingSample>,
        realData: List<Pair<List<String>, Double>>
    ) {
        for ((symptoms, probability) in realData) {
            val vec = buildInputVector(symptoms)
            existing.add(TrainingSample(vec, probability.coerceIn(0.0, 1.0)))
        }
    }

    fun debugInfo(): String = buildString {
        appendLine("=== TrainingDataGenerator Debug ===")
        appendLine("inputSize       = $inputSize")
        appendLine("aritmiaRules    = ${aritmiaIndices.size}")
        appendLine("nonAritmiaRules = ${nonAritmiaIndices.size}")
        appendLine("realisticMax    = $realisticMaxWeight")
        appendLine("Примеры target:")
        val maxRule = aritmiaIndices.maxByOrNull { rules[it].probabilityWeight }
        if (maxRule != null) {
            val vec = DoubleArray(inputSize); vec[maxRule] = 1.0
            appendLine("  1 симптом (${rules[maxRule].symptomKey}, w=${rules[maxRule].probabilityWeight}) → ${computeTargetFromVector(vec)}")
        }
        val top4 = aritmiaIndices.sortedByDescending { rules[it].probabilityWeight }.take(4)
        val vec4 = DoubleArray(inputSize); top4.forEach { vec4[it] = 1.0 }
        appendLine("  4 топ-симптома → ${computeTargetFromVector(vec4)}")
    }
}
