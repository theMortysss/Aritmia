package my.diplom.aritmia.diagnosis

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Простая MLP для многоклассовой классификации сердечно-сосудистых состояний:
 * input(symptom concepts) -> hidden(ReLU) -> output(softmax over diseases).
 */
class DiseaseNeuralNetwork(
    val inputSize: Int,
    val hiddenSize: Int,
    val outputSize: Int
) {
    private var w1 = Array(inputSize) { DoubleArray(hiddenSize) }
    private var b1 = DoubleArray(hiddenSize)
    private var w2 = Array(hiddenSize) { DoubleArray(outputSize) }
    private var b2 = DoubleArray(outputSize)

    var lastLoss: Double = Double.MAX_VALUE
        private set
    var lastEpochs: Int = 0
        private set

    init { initWeights() }

    private fun initWeights() {
        val rng = Random(42)
        val l1 = sqrt(6.0 / (inputSize + hiddenSize))
        val l2 = sqrt(6.0 / (hiddenSize + outputSize))
        for (i in 0 until inputSize) for (h in 0 until hiddenSize) {
            w1[i][h] = rng.nextDouble(-l1, l1)
        }
        for (h in 0 until hiddenSize) for (o in 0 until outputSize) {
            w2[h][o] = rng.nextDouble(-l2, l2)
        }
    }

    fun predict(input: DoubleArray): DoubleArray {
        require(input.size == inputSize)
        return forward(input).third
    }

    fun train(
        samples: List<DiseaseTrainingSample>,
        epochs: Int = 1800,
        learningRate: Double = 0.025,
        l2: Double = 0.0002,
        targetLoss: Double = 0.035
    ) {
        if (samples.isEmpty()) return
        val buffer = samples.toMutableList()

        repeat(epochs) { epoch ->
            buffer.shuffle(Random(epoch.toLong() + 99L))
            var loss = 0.0

            for (sample in buffer) {
                val (z1, hidden, probs) = forward(sample.input)
                loss += -ln(max(probs[sample.labelIndex], 1e-12))

                val dOut = probs.copyOf()
                dOut[sample.labelIndex] -= 1.0

                val dHidden = DoubleArray(hiddenSize)
                for (h in 0 until hiddenSize) {
                    var sum = 0.0
                    for (o in 0 until outputSize) sum += dOut[o] * w2[h][o]
                    dHidden[h] = if (z1[h] > 0.0) sum else 0.0
                }

                for (h in 0 until hiddenSize) {
                    for (o in 0 until outputSize) {
                        w2[h][o] -= learningRate * (dOut[o] * hidden[h] + l2 * w2[h][o])
                    }
                }
                for (o in 0 until outputSize) b2[o] -= learningRate * dOut[o]

                for (i in 0 until inputSize) {
                    for (h in 0 until hiddenSize) {
                        w1[i][h] -= learningRate * (dHidden[h] * sample.input[i] + l2 * w1[i][h])
                    }
                }
                for (h in 0 until hiddenSize) b1[h] -= learningRate * dHidden[h]
            }

            lastLoss = loss / buffer.size
            lastEpochs = epoch + 1
            if (lastLoss <= targetLoss) return
        }
    }

    private fun forward(input: DoubleArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val z1 = DoubleArray(hiddenSize) { h ->
            var value = b1[h]
            for (i in 0 until inputSize) value += input[i] * w1[i][h]
            value
        }
        val hidden = DoubleArray(hiddenSize) { h -> max(0.0, z1[h]) }
        val logits = DoubleArray(outputSize) { o ->
            var value = b2[o]
            for (h in 0 until hiddenSize) value += hidden[h] * w2[h][o]
            value
        }
        val probs = softmax(logits)
        return Triple(z1, hidden, probs)
    }

    private fun softmax(logits: DoubleArray): DoubleArray {
        val maxLogit = logits.maxOrNull() ?: 0.0
        val exps = DoubleArray(logits.size) { exp(logits[it] - maxLogit) }
        val sum = exps.sum().coerceAtLeast(1e-12)
        return DoubleArray(exps.size) { exps[it] / sum }
    }
}

data class DiseaseTrainingSample(
    val input: DoubleArray,
    val labelIndex: Int
)
