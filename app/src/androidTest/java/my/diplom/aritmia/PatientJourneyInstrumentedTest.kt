package my.diplom.aritmia

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private val prefs
        get() = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    @After
    fun clearPersistedSession() {
        prefs.edit().clear().commit()
    }

    @Test
    fun patientCanRegisterContinueAbstentionRankPersistLoginAndRestoreSession() {
        clearPersistedSession()

        val phone = uniquePhone()
        val password = "test123"
        val threeComplaints = listOf(
            "нерегулярный пульс",
            "сердце стучит",
            "кружится голова"
        )
        val fourthComplaint = "не хватает воздуха"
        val fourComplaints = threeComplaints + fourthComplaint

        var scenario: ActivityScenario<MainActivity>? = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var db: AppDatabase
            scenario!!.onActivity { activity -> db = activity.db }
            waitForSeedRules(db)
            waitForText("Вход в приложение", stage = "initial login screen")

            registerPatient(phone, password)
            val patientId = waitForPatientSession(
                stage = "patient registration",
                timeoutMillis = 30_000
            )
            waitForText(
                "Симптомы пока не добавлены",
                stage = "navigation after patient registration",
                timeoutMillis = 30_000
            )
            assertTrue("Registration must persist the patient session", patientId > 0)

            threeComplaints.forEach(::addComplaint)
            composeRule.onNodeWithText("Диагностировать").performClick()
            waitForText(
                "Недостаточно признаков для ранжирования заболеваний",
                stage = "three-concept abstention result"
            )
            waitForTextContaining(
                "Распознано сердечно-сосудистых признаков: 3.",
                stage = "three-concept evidence count"
            )

            val afterAbstention = runBlocking { db.symptomDao().getSymptomsByPatientId(patientId) }
            assertEquals(1, afterAbstention.size)
            assertEquals(threeComplaints.joinToString(". "), afterAbstention.single().userInput)

            composeRule.onNodeWithText("Дополнить жалобы")
                .performScrollTo()
                .performClick()
            threeComplaints.forEach { complaint ->
                waitForText(complaint, stage = "restoring complaint '$complaint' for follow-up")
            }

            addComplaint(fourthComplaint)
            composeRule.onNodeWithText("Диагностировать").performClick()
            waitForText(
                "Возможные сердечно-сосудистые состояния",
                stage = "four-concept ranked result"
            )
            waitForTextContaining(
                "Фибрилляция / трепетание предсердий",
                stage = "atrial fibrillation top candidate"
            )

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
            waitForText(
                "Вы уверены, что хотите выйти?",
                stage = "logout confirmation dialog"
            )
            composeRule.onNodeWithText("Да").performClick()
            composeRule.waitForIdle()
            waitForText(
                "Вход в приложение",
                stage = "login screen after logout",
                timeoutMillis = 15_000
            )
            assertEquals(
                "Logout must clear the persisted patient session before showing login",
                -1,
                prefs.getInt("current_patient_id", -1)
            )

            loginPatient(phone, password)
            waitForPatientSession(
                expectedPatientId = patientId,
                stage = "patient login",
                timeoutMillis = 30_000
            )
            waitForText(
                "Симптомы пока не добавлены",
                stage = "navigation after patient login",
                timeoutMillis = 30_000
            )

            scenario!!.close()
            scenario = null

            ActivityScenario.launch(MainActivity::class.java).use {
                waitForText(
                    "Симптомы пока не добавлены",
                    stage = "patient session restore after activity relaunch",
                    timeoutMillis = 30_000
                )
                assertTrue(
                    "A restored patient session must not return to login",
                    composeRule.onAllNodesWithText("Вход в приложение")
                        .fetchSemanticsNodes()
                        .isEmpty()
                )
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
        waitForText(text, stage = "adding complaint '$text'")
    }

    private fun replaceEditableField(index: Int, value: String) {
        composeRule.onAllNodes(hasSetTextAction())[index]
            .performScrollTo()
            .performTextReplacement(value)
    }

    private fun waitForText(
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

    private fun waitForTextContaining(
        text: String,
        stage: String,
        timeoutMillis: Long = 15_000
    ) {
        try {
            composeRule.waitUntil(timeoutMillis) {
                composeRule.onAllNodesWithText(text, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError("Timed out during $stage waiting for text containing '$text'", error)
        }
    }

    private fun waitForPatientSession(
        expectedPatientId: Int? = null,
        stage: String,
        timeoutMillis: Long
    ): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val patientId = prefs.getInt("current_patient_id", -1)
            if (patientId > 0 && (expectedPatientId == null || patientId == expectedPatientId)) {
                return patientId
            }
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "Timed out during $stage waiting for persisted patient session. " +
                "Current patient id=${prefs.getInt("current_patient_id", -1)}; " +
                "visible auth error=${visibleAuthError() ?: "none"}"
        )
    }

    private fun visibleAuthError(): String? {
        val errors = listOf(
            "ФИО должно содержать только буквы, слова с заглавной буквы",
            "Телефон должен содержать ровно 10 цифр",
            "Возраст должен быть от 1 до 150",
            "Пароль должен содержать минимум 6 символов",
            "Пользователь с таким номером и ролью уже существует",
            "Неверный телефон или пароль"
        )
        return errors.firstOrNull { message ->
            composeRule.onAllNodesWithText(message, substring = false)
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
