package my.diplom.aritmia

import android.content.Context
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import my.diplom.aritmia.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PatientJourneyInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun clearPersistedSession() {
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun patientCanRegisterAbstainRankPersistLoginAndRestoreSession() {
        clearPersistedSession()

        val phone = uniquePhone()
        val password = "test123"
        val threeComplaints = listOf(
            "нерегулярный пульс",
            "сердце стучит",
            "кружится голова"
        )
        val fourComplaints = threeComplaints + "не хватает воздуха"

        var scenario: ActivityScenario<MainActivity>? = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var db: AppDatabase
            scenario!!.onActivity { activity -> db = activity.db }
            waitForSeedRules(db)
            waitForText("Вход в приложение")

            registerPatient(phone, password)
            waitForText("Симптомы пока не добавлены")

            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val patientId = prefs.getInt("current_patient_id", -1)
            assertTrue("Registration must persist the patient session", patientId > 0)

            threeComplaints.forEach(::addComplaint)
            composeRule.onNodeWithText("Диагностировать").performClick()
            waitForText("Недостаточно признаков для ранжирования заболеваний")
            composeRule.onNodeWithText("Распознано сердечно-сосудистых признаков: 3.").assertExists()

            val afterAbstention = runBlocking { db.symptomDao().getSymptomsByPatientId(patientId) }
            assertEquals(1, afterAbstention.size)
            assertEquals(threeComplaints.joinToString(". "), afterAbstention.single().userInput)

            composeRule.onNodeWithText("Назад к вводу симптомов")
                .performScrollTo()
                .performClick()
            waitForText("Симптомы пока не добавлены")

            fourComplaints.forEach(::addComplaint)
            composeRule.onNodeWithText("Диагностировать").performClick()
            waitForText("Возможные сердечно-сосудистые состояния")
            composeRule.onNodeWithText("Фибрилляция / трепетание предсердий", substring = true)
                .assertExists()

            val afterRanking = runBlocking { db.symptomDao().getSymptomsByPatientId(patientId) }
            assertEquals(2, afterRanking.size)
            assertEquals(
                setOf(
                    threeComplaints.joinToString(". "),
                    fourComplaints.joinToString(". ")
                ),
                afterRanking.map { it.userInput }.toSet()
            )

            composeRule.onNodeWithText("Выйти").performClick()
            waitForText("Вы уверены, что хотите выйти?")
            composeRule.onNodeWithText("Да").performClick()
            waitForText("Вход в приложение")
            assertEquals(-1, prefs.getInt("current_patient_id", -1))

            loginPatient(phone, password)
            waitForText("Симптомы пока не добавлены")
            assertEquals(patientId, prefs.getInt("current_patient_id", -1))

            // Close the real Activity and launch a brand-new one. MainActivity must reread
            // the persisted patient id, validate it against Room and restore the symptoms route.
            scenario!!.close()
            scenario = null

            ActivityScenario.launch(MainActivity::class.java).use {
                waitForText("Симптомы пока не добавлены")
                composeRule.onNodeWithText("Вход в приложение").assertDoesNotExist()
                assertEquals(patientId, prefs.getInt("current_patient_id", -1))
            }
        } finally {
            scenario?.close()
        }
    }

    private fun registerPatient(phone: String, password: String) {
        composeRule.onNodeWithText("Регистрация").performClick()
        composeRule.waitForIdle()

        replaceEditableField(0, "Тест Пациент")
        replaceEditableField(1, phone)
        replaceEditableField(3, "34")
        replaceEditableField(4, password)

        composeRule.onNodeWithText("Зарегистрироваться")
            .performScrollTo()
            .performClick()
    }

    private fun loginPatient(phone: String, password: String) {
        composeRule.waitForIdle()
        replaceEditableField(0, phone)
        replaceEditableField(1, password)
        composeRule.onNodeWithText("Войти").performScrollTo().performClick()
    }

    private fun addComplaint(text: String) {
        composeRule.waitForIdle()
        val input = composeRule.onAllNodes(hasSetTextAction())[0]
        input.performTextReplacement(text)
        input.performImeAction()
        waitForText(text)
    }

    private fun replaceEditableField(index: Int, value: String) {
        composeRule.onAllNodes(hasSetTextAction())[index]
            .performScrollTo()
            .performTextReplacement(value)
    }

    private fun waitForText(text: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithText(text, substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForSeedRules(db: AppDatabase) = runBlocking {
        repeat(100) {
            if (db.ruleDao().getAllRules().isNotEmpty()) return@runBlocking
            delay(50)
        }
        error("Initial rule seed did not complete")
    }

    private fun uniquePhone(): String {
        val suffix = (System.currentTimeMillis() % 1_000_000_000L)
            .toString()
            .padStart(9, '0')
        return "9$suffix"
    }
}
