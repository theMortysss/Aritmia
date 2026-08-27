package my.diplom.aritmia.data

import my.diplom.aritmia.diagnosis.ComplaintTriageFlag
import my.diplom.aritmia.diagnosis.ComplaintTriageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssessmentSnapshotCodecTest {

    @Test
    fun triageFlagsRoundTripKeepsHistoricalMessageAndStableIds() {
        val encoded = AssessmentSnapshotCodec.encodeTriageFlags(
            listOf(
                ComplaintTriageFlag(
                    id = "acute_chest_symptoms",
                    level = ComplaintTriageLevel.EMERGENCY,
                    title = "Острый дискомфорт в груди требует срочной оценки",
                    message = "Сохранённое сообщение для врача",
                    matchedConceptIds = setOf("chest_pressure", "chest_pain")
                )
            )
        )

        val decoded = AssessmentSnapshotCodec.decodeTriageFlags(encoded)

        assertEquals(1, decoded.size)
        assertEquals("acute_chest_symptoms", decoded.single().id)
        assertEquals("EMERGENCY", decoded.single().level)
        assertEquals("Острый дискомфорт в груди требует срочной оценки", decoded.single().title)
        assertEquals("Сохранённое сообщение для врача", decoded.single().message)
        assertEquals(listOf("chest_pain", "chest_pressure"), decoded.single().matchedConceptIds)
    }

    @Test
    fun noTriageFlagsUsesNullSnapshotAndDecodesAsEmpty() {
        val encoded = AssessmentSnapshotCodec.encodeTriageFlags(emptyList())

        assertNull(encoded)
        assertEquals(emptyList<StoredTriageFlag>(), AssessmentSnapshotCodec.decodeTriageFlags(encoded))
    }
}
