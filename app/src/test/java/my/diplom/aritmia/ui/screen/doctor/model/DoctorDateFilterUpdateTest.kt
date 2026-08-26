package my.diplom.aritmia.ui.screen.doctor.model

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoctorDateFilterUpdateTest {

    private val current = LocalDateTime.of(2026, 8, 26, 12, 0)

    @Test
    fun omittedDateKeepsCurrentValue() {
        assertEquals(
            current,
            resolveDateFilterUpdate(current, UNCHANGED_DATE_FILTER)
        )
    }

    @Test
    fun explicitNullClearsCurrentValue() {
        assertNull(resolveDateFilterUpdate(current, null))
    }

    @Test
    fun explicitDateReplacesCurrentValue() {
        val replacement = LocalDateTime.of(2026, 8, 20, 8, 30)
        assertEquals(replacement, resolveDateFilterUpdate(current, replacement))
    }
}
