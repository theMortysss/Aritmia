package my.diplom.aritmia.ui.screen.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoctorTriagePresentationTest {

    @Test
    fun triagePriorityOrdersEmergencyBeforeReviewBeforeNeutral() {
        assertEquals(2, doctorTriagePriority("EMERGENCY"))
        assertEquals(1, doctorTriagePriority("MEDICAL_REVIEW"))
        assertEquals(0, doctorTriagePriority("NONE"))
        assertEquals(0, doctorTriagePriority("UNKNOWN"))
    }

    @Test
    fun triageLabelsAreOnlyShownForActionableStoredLevels() {
        assertEquals("Экстренная оценка", doctorTriageLabel("EMERGENCY"))
        assertEquals("Медицинская оценка", doctorTriageLabel("MEDICAL_REVIEW"))
        assertNull(doctorTriageLabel("NONE"))
        assertNull(doctorTriageLabel("UNKNOWN"))
    }
}
