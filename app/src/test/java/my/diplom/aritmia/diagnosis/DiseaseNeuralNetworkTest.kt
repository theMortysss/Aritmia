package my.diplom.aritmia.diagnosis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiseaseNeuralNetworkTest {

    @Test
    fun softmaxOutputSumsToOne() {
        val model = DiseaseNeuralNetwork(inputSize = 3, hiddenSize = 4, outputSize = 2)
        val probabilities = model.predict(doubleArrayOf(1.0, 0.0, 1.0))

        assertEquals(2, probabilities.size)
        assertEquals(1.0, probabilities.sum(), 1e-9)
        assertTrue(probabilities.all { it in 0.0..1.0 })
    }

    @Test
    fun loadedSnapshotControlsPrediction() {
        val model = DiseaseNeuralNetwork(inputSize = 2, hiddenSize = 2, outputSize = 2)
        val snapshot = DiseaseModelSnapshot(
            formatVersion = 1,
            modelType = "aritmia_symptom_multiclass_mlp",
            inputConceptIds = listOf("a", "b"),
            outputDiseaseIds = listOf("x", "y"),
            hiddenSize = 2,
            activation = "relu",
            outputActivation = "softmax",
            weightsInputHidden = listOf(
                listOf(0.0, 0.0),
                listOf(0.0, 0.0)
            ),
            biasHidden = listOf(0.0, 0.0),
            weightsHiddenOutput = listOf(
                listOf(0.0, 0.0),
                listOf(0.0, 0.0)
            ),
            biasOutput = listOf(0.0, 2.0)
        )

        model.loadWeights(snapshot)
        val probabilities = model.predict(doubleArrayOf(1.0, 1.0))

        assertTrue(probabilities[1] > probabilities[0])
        assertEquals(1.0, probabilities.sum(), 1e-9)
    }
}
