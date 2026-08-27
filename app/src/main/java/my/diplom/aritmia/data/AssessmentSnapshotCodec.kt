package my.diplom.aritmia.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import my.diplom.aritmia.diagnosis.ComplaintTriageFlag
import my.diplom.aritmia.diagnosis.DiseaseCandidate

@Serializable
data class StoredDiseaseCandidate(
    val id: String,
    val name: String,
    val modelScorePercent: Int,
    val matchedSignals: List<String> = emptyList()
)

@Serializable
data class StoredTriageFlag(
    val id: String,
    val level: String,
    val title: String,
    val message: String,
    val matchedConceptIds: List<String> = emptyList()
)

object AssessmentSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeConceptIds(ids: Set<String>): String =
        json.encodeToString(ids.sorted())

    fun decodeConceptIds(raw: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    fun encodeCandidates(candidates: List<DiseaseCandidate>): String? =
        candidates.takeIf { it.isNotEmpty() }
            ?.map { candidate ->
                StoredDiseaseCandidate(
                    id = candidate.id,
                    name = candidate.name,
                    modelScorePercent = candidate.modelScorePercent,
                    matchedSignals = candidate.matchedSignals
                )
            }
            ?.let { json.encodeToString(it) }

    fun decodeCandidates(raw: String?): List<StoredDiseaseCandidate> =
        raw?.let { encoded ->
            runCatching { json.decodeFromString<List<StoredDiseaseCandidate>>(encoded) }
                .getOrDefault(emptyList())
        } ?: emptyList()

    fun encodeTriageFlags(flags: List<ComplaintTriageFlag>): String? =
        flags.takeIf { it.isNotEmpty() }
            ?.map { flag ->
                StoredTriageFlag(
                    id = flag.id,
                    level = flag.level.name,
                    title = flag.title,
                    message = flag.message,
                    matchedConceptIds = flag.matchedConceptIds.sorted()
                )
            }
            ?.let { json.encodeToString(it) }

    fun decodeTriageFlags(raw: String?): List<StoredTriageFlag> =
        raw?.let { encoded ->
            runCatching { json.decodeFromString<List<StoredTriageFlag>>(encoded) }
                .getOrDefault(emptyList())
        } ?: emptyList()
}
