package my.diplom.aritmia

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PatientSessionRestoreInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    @Test
    fun savedActivePatientSessionStartsDirectlyOnSymptoms() {
        prefs.edit().clear().commit()

        lateinit var db: AppDatabase
        ActivityScenario.launch(MainActivity::class.java).use { bootstrap ->
            bootstrap.onActivity { activity -> db = activity.db }
            waitForExactText("Вход в приложение", "bootstrap login")
        }

        val phone = uniquePhone()
        val patient = runBlocking {
            db.userDao().insert(
                User(
                    phone = phone,
                    fullName = "Тест Восстановление",
                    password = "unused",
                    role = Role.PATIENT,
                    age = 42,
                    isActive = true
                )
            )
            requireNotNull(db.userDao().getPatientByPhone(phone))
        }

        try {
            prefs.edit().putInt("current_patient_id", patient.id).commit()

            ActivityScenario.launch(MainActivity::class.java).use {
                waitForExactText(
                    "Симптомы пока не добавлены",
                    "restored patient start destination",
                    timeoutMillis = 30_000
                )
                assertTrue(
                    "A valid restored patient session must not show login",
                    composeRule.onAllNodesWithText("Вход в приложение", substring = false)
                        .fetchSemanticsNodes()
                        .isEmpty()
                )
                assertEquals(patient.id, prefs.getInt("current_patient_id", -1))
            }
        } finally {
            prefs.edit().clear().commit()
            runBlocking {
                db.userDao().getPatientByPhone(phone)?.let { db.userDao().delete(it) }
            }
        }
    }

    private fun waitForExactText(
        text: String,
        stage: String,
        timeoutMillis: Long = 15_000
    ) {
        try {
            composeRule.waitUntil(timeoutMillis) {
                composeRule.onAllNodesWithText(text, substring = false)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError("Timed out during $stage waiting for exact text '$text'", error)
        }
    }

    private fun uniquePhone(): String {
        val suffix = (System.currentTimeMillis() % 1_000_000_000L)
            .toString()
            .padStart(9, '0')
        return "8$suffix"
    }
}
