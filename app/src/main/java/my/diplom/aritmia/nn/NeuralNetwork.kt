package my.diplom.aritmia.nn

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Двухслойный сигмоидальный персептрон (MLP).
 *
 * Архитектура:
 *   Вход (inputSize) → Скрытый слой (hiddenSize, sigmoid) → Выход (1, sigmoid)
 *
 * Обучение: метод обратного распространения ошибки (backpropagation),
 * offline-режим (shuffle + итерации по эпохам), функция потерь — MSE.
 *
 * @param inputSize  размер входного вектора (число симптомов-признаков)
 * @param hiddenSize число нейронов скрытого слоя
 */
class NeuralNetwork(
    val inputSize: Int,
    val hiddenSize: Int = 15
) {
    // ── Веса и смещения ────────────────────────────────────────────────────────
    /** weightsIH[i][j] — вес от входного нейрона i к скрытому j */
    var weightsIH: Array<DoubleArray> = Array(inputSize) { DoubleArray(hiddenSize) }

    /** weightsHO[j] — вес от скрытого нейрона j к выходному нейрону */
    var weightsHO: DoubleArray = DoubleArray(hiddenSize)

    var biasH: DoubleArray = DoubleArray(hiddenSize)
    var biasO: Double = 0.0

    // ── Метрики обучения ───────────────────────────────────────────────────────
    var lastTrainLoss: Double = Double.MAX_VALUE
        private set
    var lastEpochsRun: Int = 0
        private set

    init { initWeightsXavier() }

    // ── Инициализация Ксавьера (Xavier / Glorot uniform) ──────────────────────
    private fun initWeightsXavier() {
        val limitIH = sqrt(6.0 / (inputSize + hiddenSize))
        val limitHO = sqrt(6.0 / (hiddenSize + 1))
        val rng = Random(42)
        for (i in 0 until inputSize)
            for (j in 0 until hiddenSize)
                weightsIH[i][j] = rng.nextDouble(-limitIH, limitIH)
        for (j in 0 until hiddenSize)
            weightsHO[j] = rng.nextDouble(-limitHO, limitHO)
        biasH = DoubleArray(hiddenSize) { rng.nextDouble(-0.1, 0.1) }
        biasO = rng.nextDouble(-0.1, 0.1)
    }

    // ── Функция активации ──────────────────────────────────────────────────────
    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /** Производная сигмоиды через выходное значение: f'(y) = y * (1 - y) */
    private fun sigmoidDeriv(y: Double): Double = y * (1.0 - y)

    // ── Прямое распространение ─────────────────────────────────────────────────
    fun forward(input: DoubleArray): Double {
        require(input.size == inputSize) {
            "Ожидается вектор размером $inputSize, получен ${input.size}"
        }
        return computeOutput(computeHidden(input))
    }

    private fun computeHidden(input: DoubleArray): DoubleArray =
        DoubleArray(hiddenSize) { j ->
            var s = biasH[j]
            for (i in 0 until inputSize) s += input[i] * weightsIH[i][j]
            sigmoid(s)
        }

    private fun computeOutput(h: DoubleArray): Double {
        var s = biasO
        for (j in 0 until hiddenSize) s += h[j] * weightsHO[j]
        return sigmoid(s)
    }

    // ── Обратное распространение ошибки ───────────────────────────────────────
    /**
     * Обучает сеть методом backpropagation.
     *
     * Алгоритм одной эпохи:
     *  1. Перемешать выборку
     *  2. Для каждого примера (x, t):
     *     a) forward: hOut, yOut
     *     b) error = t - yOut
     *     c) δO = error * sigmoid'(yOut)
     *     d) δH[j] = δO * wHO[j] * sigmoid'(hOut[j])
     *     e) wHO[j] += η * δO * hOut[j];  biasO += η * δO
     *     f) wIH[i][j] += η * δH[j] * x[i];  biasH[j] += η * δH[j]
     *  3. MSE = totalLoss / N; если MSE < targetLoss → стоп
     *
     * @param samples       обучающие примеры
     * @param epochs        максимальное число эпох
     * @param learningRate  скорость обучения η
     * @param targetLoss    порог MSE для ранней остановки
     * @param onEpoch       колбэк (epoch, mse)
     */
    fun train(
        samples: List<TrainingSample>,
        epochs: Int = 5000,
        learningRate: Double = 0.05,
        targetLoss: Double = 0.0015,
        onEpoch: ((epoch: Int, mse: Double) -> Unit)? = null
    ) {
        val buf = samples.toMutableList()

        for (epoch in 0 until epochs) {
            buf.shuffle(Random(epoch.toLong()))
            var totalLoss = 0.0

            for ((input, target) in buf) {
                // forward
                val hOut = computeHidden(input)
                val yOut = computeOutput(hOut)

                val error = target - yOut
                totalLoss += error * error

                // output delta
                val dO = error * sigmoidDeriv(yOut)

                // hidden deltas
                val dH = DoubleArray(hiddenSize) { j ->
                    dO * weightsHO[j] * sigmoidDeriv(hOut[j])
                }

                // update hidden → output
                for (j in 0 until hiddenSize) weightsHO[j] += learningRate * dO * hOut[j]
                biasO += learningRate * dO

                // update input → hidden
                for (i in 0 until inputSize)
                    for (j in 0 until hiddenSize)
                        weightsIH[i][j] += learningRate * dH[j] * input[i]
                for (j in 0 until hiddenSize) biasH[j] += learningRate * dH[j]
            }

            val mse = totalLoss / buf.size
            lastTrainLoss = mse
            lastEpochsRun = epoch + 1
            onEpoch?.invoke(epoch, mse)
            if (mse < targetLoss) break
        }
    }

    // ── Сериализация ───────────────────────────────────────────────────────────
    fun serialize() = NetworkSnapshot(
        inputSize  = inputSize,
        hiddenSize = hiddenSize,
        weightsIH  = weightsIH.map { it.toList() },
        weightsHO  = weightsHO.toList(),
        biasH      = biasH.toList(),
        biasO      = biasO
    )

    companion object {
        fun fromSnapshot(s: NetworkSnapshot): NeuralNetwork {
            val nn = NeuralNetwork(s.inputSize, s.hiddenSize)
            nn.weightsIH = Array(s.weightsIH.size) { i -> s.weightsIH[i].toDoubleArray() }
            nn.weightsHO = s.weightsHO.toDoubleArray()
            nn.biasH     = s.biasH.toDoubleArray()
            nn.biasO     = s.biasO
            return nn
        }
    }
}

// ── Data-классы ────────────────────────────────────────────────────────────────

/** Один обучающий пример */
class TrainingSample(
    val input: DoubleArray,
    val target: Double
) {
    operator fun component1() = input
    operator fun component2() = target
}

/** Снимок весов для JSON-сериализации */
@Serializable
data class NetworkSnapshot(
    val inputSize:  Int,
    val hiddenSize: Int,
    val weightsIH:  List<List<Double>>,
    val weightsHO:  List<Double>,
    val biasH:      List<Double>,
    val biasO:      Double
)
