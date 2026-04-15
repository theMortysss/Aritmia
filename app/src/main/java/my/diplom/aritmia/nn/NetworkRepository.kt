package my.diplom.aritmia.nn

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import my.diplom.aritmia.data.RuleEntity
import java.io.File

class NetworkRepository(private val context: Context) {

    companion object {
        private const val TAG          = "NetworkRepository"
        private const val WEIGHTS_FILE = "nn_weights.json"
        private const val HIDDEN_SIZE  = 15
        private const val TRAIN_EPOCHS = 8000
        private const val LEARN_RATE   = 0.03
        private const val TARGET_LOSS  = 0.001
        private const val TRAIN_SAMPLES = 1000
    }

    @Volatile private var network:   NeuralNetwork?          = null
    @Volatile private var generator: TrainingDataGenerator?  = null

    private val json = Json { prettyPrint = true }

    suspend fun initialize(rules: List<RuleEntity>) = withContext(Dispatchers.IO) {
        val gen = TrainingDataGenerator(rules)
        generator = gen
        Log.d(TAG, gen.debugInfo())

        val loaded = tryLoadWeights(gen.inputSize)
        if (loaded != null) {
            network = loaded
            Log.i(TAG, "Веса загружены. Path=${weightsFile().absolutePath}")
        } else {
            Log.i(TAG, "Начинаем обучение. inputSize=${gen.inputSize}, samples=$TRAIN_SAMPLES")
            val net = trainFresh(gen)
            network = net
            saveWeights(net)
        }
    }

    suspend fun retrain(rules: List<RuleEntity>) = withContext(Dispatchers.IO) {
        val gen = TrainingDataGenerator(rules)
        generator = gen
        weightsFile().delete()          // сбрасываем кэш
        val net = trainFresh(gen)
        network = net
        saveWeights(net)
        Log.i(TAG, "Переобучение завершено. loss=${net.lastTrainLoss}, epochs=${net.lastEpochsRun}")
    }

    fun predict(symptoms: List<String>): Double? {
        val gen = generator ?: return null
        val net = network   ?: return null
        val vec = gen.buildInputVector(symptoms)
        val raw = net.forward(vec)
        Log.d(TAG, "predict: symptoms=${symptoms.size}, vec_sum=${vec.sum()}, raw=$raw")
        return raw
    }

    fun isReady(): Boolean = network != null && generator != null

    fun getGenerator(): TrainingDataGenerator? = generator
    fun getLastLoss():  Double = network?.lastTrainLoss ?: Double.MAX_VALUE
    fun getLastEpochs(): Int   = network?.lastEpochsRun ?: 0

    private fun trainFresh(gen: TrainingDataGenerator): NeuralNetwork {
        val net     = NeuralNetwork(inputSize = gen.inputSize, hiddenSize = HIDDEN_SIZE)
        val samples = gen.generateSamples(totalSamples = TRAIN_SAMPLES)

        val minTarget = samples.minOf { it.target }
        val maxTarget = samples.maxOf { it.target }
        Log.d(TAG, "Samples: n=${samples.size}, targetMin=$minTarget, targetMax=$maxTarget")

        net.train(
            samples      = samples,
            epochs       = TRAIN_EPOCHS,
            learningRate = LEARN_RATE,
            targetLoss   = TARGET_LOSS,
            onEpoch      = { epoch, mse ->
                if (epoch % 1000 == 0) Log.d(TAG, "Epoch $epoch  MSE=${"%.6f".format(mse)}")
            }
        )
        Log.i(TAG, "Обучение OK: loss=${net.lastTrainLoss}, epochs=${net.lastEpochsRun}")
        return net
    }

    private fun tryLoadWeights(expectedInputSize: Int): NeuralNetwork? {
        return try {
            val file = weightsFile()
            if (!file.exists()) return null
            val snap = json.decodeFromString<NetworkSnapshot>(file.readText())
            if (snap.inputSize != expectedInputSize) {
                Log.w(TAG, "inputSize изменился (${snap.inputSize}→$expectedInputSize), переобучаем")
                file.delete()
                return null
            }
            NeuralNetwork.fromSnapshot(snap)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки весов: ${e.message}")
            null
        }
    }

    private fun saveWeights(net: NeuralNetwork) {
        try {
            val file = weightsFile()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(net.serialize()))
            Log.i(TAG, "Веса сохранены → ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения весов: ${e.message}")
        }
    }

    private fun weightsFile(): File {
        val externalDir = context.getExternalFilesDir(null)
        return if (externalDir != null) {
            File(externalDir, "nn/$WEIGHTS_FILE")
        } else {
            Log.w(TAG, "External storage недоступен, используем internal storage")
            File(context.filesDir, "nn/$WEIGHTS_FILE")
        }
    }

    fun weightsFilePath(): String = weightsFile().absolutePath
}
