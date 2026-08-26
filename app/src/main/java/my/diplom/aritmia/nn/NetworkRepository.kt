package my.diplom.aritmia.nn

import android.content.Context
import my.diplom.aritmia.data.RuleEntity

/**
 * Transitional compatibility shim for call sites that historically depended on the
 * old binary arrhythmia MLP. The legacy model has been retired: it is no longer
 * trained, loaded, cached, or used for predictions.
 *
 * This class can be removed completely after the remaining UI/rule-management call
 * sites are migrated away from the old API.
 */
@Suppress("UNUSED_PARAMETER")
class NetworkRepository(context: Context) {

    suspend fun initialize(rules: List<RuleEntity>) = Unit

    suspend fun retrain(rules: List<RuleEntity>) = Unit

    fun predict(symptoms: List<String>): Double? = null

    fun isReady(): Boolean = true

    fun getLastLoss(): Double = Double.NaN

    fun getLastEpochs(): Int = 0
}
