package my.diplom.aritmia

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import my.diplom.aritmia.app.AritmiaApp
import my.diplom.aritmia.data.AssessmentEntity
import my.diplom.aritmia.data.AssessmentSnapshotCodec
import my.diplom.aritmia.data.AssessmentWorkflow
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.User
import my.diplom.aritmia.security.PasswordHasher
import my.diplom.aritmia.utils.formatPhoneNumber
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PatientDoctorEmergencyJourneyInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    private val db
        get() = (context as AritmiaApp).appDatabase

    @After
    fun clearPersistedSession() {
        prefs.edit().clear().commit()
    }

    @Test
    fun patientEmergencyTriagePersistsAndReachesDoctorWorkflow() {
        clearPersistedSession()

        val doctorPhone = uniquePhone(1)
        val patientPhone = uniquePhone(2)
        val doctorPassword = "doctor123"
        val patientPassword = "patient123"
        val patientName = "Срочный Пациент"
        val complaint = "Внезапно сильная боль в спине"
        val aorticFlagTitle = "Внезапная сильная боль в спине или животе требует срочной оценки"

        createDoctor(
            phoneDigits = doctorPhone,
            fullName = "Врач Срочность",
            password = doctorPassword
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForSeedRules()
            waitForText("Вход в приложение", "initial login screen")

            registerPatient(
                fullName = patientName,
                phone = patientPhone,
                password = patientPassword
            )
            val patientId = waitForPatientSession("patient registration")
            waitForText(
                "Симптомы пока не добавлены",
                "navigation after patient registration",
                30_000
            )

            addComplaint(complaint)
            clickExactText("Диагностировать")
            completeClarificationsWithoutGuessing()

            waitForText(
                "Нужна срочная медицинская оценка",
                "patient emergency triage result",
                30_000
            )
            waitForText(aorticFlagTitle, "patient acute-aortic warning")
            assertFalse(
                "Emergency triage must not invite the patient to add complaints before seeking care",
                hasExactText("Дополнить жалобы")
            )

            val source = waitForSinglePatientSymptom(patientId)
            assertTrue(
                "This scenario must prove urgency without a guessed clarification answer",
                source.clarifyingAnswers.orEmpty().contains("не могу ответить", ignoreCase = true)
            )

            val assessment = waitForAssessment(patientId)
            assertEquals("INSUFFICIENT_EVIDENCE", assessment.status)
            assertEquals("EMERGENCY", assessment.triageLevel)
            assertTrue(assessment.needsDoctorAttention)
            val storedFlags = AssessmentSnapshotCodec.decodeTriageFlags(assessment.triageFlags)
            val aorticFlag = storedFlags.firstOrNull { it.id == "acute_aortic_pain_pattern" }
                ?: throw AssertionError("Persisted emergency assessment lost acute_aortic_pain_pattern")
            assertEquals("EMERGENCY", aorticFlag.level)
            assertTrue("back_pain" in aorticFlag.matchedConceptIds)
            assertEquals(aorticFlagTitle, aorticFlag.title)

            logoutToLogin("patient emergency result")

            loginDoctor(doctorPhone, doctorPassword)
            waitForText("Меню врача", "doctor workspace", 30_000)
            waitForText(patientName, "patient visible in doctor queue", 30_000)
            waitForText("Срочность: Экстренная оценка", "saved emergency level in doctor queue")
            waitForText(aorticFlagTitle, "saved emergency reason in doctor queue")

            composeRule.onNodeWithTag("doctor_open_assessment_${assessment.id}").performClick()
            waitForText("Обращение пациента", "doctor assessment details")
            waitForText("Сохранённая оценка срочности", "persisted triage section")
            waitForText("Уровень: Экстренная оценка", "persisted emergency level")
            waitForText(aorticFlagTitle, "persisted emergency reason")
            waitForText(complaint, "original patient complaint in doctor details")

            replaceEditableField(
                index = 0,
                value = "Требуется срочно связаться с пациентом",
                scrollTo = true
            )
            val contactRequired = composeRule.onNodeWithTag("doctor_workflow_CONTACT_REQUIRED")
            if (!contactRequired.isDisplayed()) contactRequired.performScrollTo()
            contactRequired.performClick()

            val updated = waitForWorkflow(assessment.id, AssessmentWorkflow.CONTACT_REQUIRED)
            assertEquals("Требуется срочно связаться с пациентом", updated.doctorNote)
            assertTrue(
                "CONTACT_REQUIRED must keep an emergency case in the attention queue",
                updated.needsDoctorAttention
            )
            assertEquals("EMERGENCY", updated.triageLevel)
            assertEquals(assessment.triageFlags, updated.triageFlags)

            clickExactText("Закрыть")
            waitForText(patientName, "doctor queue after workflow update")
            waitForText("Работа врача: Нужно связаться", "saved doctor workflow in queue")

            logoutToLogin("doctor workspace")
            composeRule.waitForIdle()
        }
    }

    private fun createDoctor(
        phoneDigits: String,
        fullName: String,
        password: String
    ): User = runBlocking {
        val formatted = formatPhoneNumber(phoneDigits)
        db.userDao().insert(
            User(
                phone = formatted,
                fullName = fullName,
                password = PasswordHasher.hash(password),
                role = Role.DOCTOR,
                specialty = "Кардиолог",
                isActive = true
            )
        )
        db.userDao().getUserByPhoneAndRole(formatted, Role.DOCTOR)
            ?: error("Doctor fixture was not persisted")
    }

    private fun registerPatient(fullName: String, phone: String, password: String) {
        clickExactText("Регистрация")
        composeRule.waitForIdle()

        replaceEditableField(0, fullName)
        replaceEditableField(1, phone)
        replaceEditableField(3, "42")
        replaceEditableField(4, password)
        clickExactText("Зарегистрироваться", scrollTo = true)
    }

    private fun loginDoctor(phone: String, password: String) {
        replaceEditableField(0, phone)
        replaceEditableField(1, password)
        clickExactText("Врач", scrollTo = true)
        clickExactText("Войти", scrollTo = true)
    }

    private fun addComplaint(text: String) {
        composeRule.waitForIdle()
        val input = composeRule.onAllNodes(hasSetTextAction())[0]
        input.performTextReplacement(text)
        input.performImeAction()
        waitForText(text, "adding complaint '$text'")
    }

    private fun completeClarificationsWithoutGuessing() {
        waitForText(
            "Не могу ответить — продолжить",
            "clarification screen",
            20_000
        )
        composeRule.waitForIdle()
        clickExactText("Не могу ответить — продолжить", scrollTo = true)
        composeRule.waitForIdle()
    }

    private fun logoutToLogin(stage: String) {
        clickExactText("Выйти")
        waitForText("Вы уверены, что хотите выйти?", "$stage logout confirmation")
        clickExactText("Да")
        waitForText("Вход в приложение", "$stage clean login stack", 30_000)
    }

    private fun waitForPatientSession(
        stage: String,
        timeoutMillis: Long = 30_000
    ): Int {
        var patientId = -1
        try {
            composeRule.waitUntil(timeoutMillis) {
                patientId = prefs.getInt("current_patient_id", -1)
                patientId > 0
            }
        } catch (error: Throwable) {
            throw AssertionError(
                "Timed out during $stage waiting for patient session; actual=$patientId",
                error
            )
        }
        return patientId
    }

    private fun waitForSinglePatientSymptom(
        patientId: Int,
        timeoutMillis: Long = 30_000
    ) = run {
        var latest = emptyList<my.diplom.aritmia.data.SymptomEntity>()
        try {
            composeRule.waitUntil(timeoutMillis) {
                latest = runBlocking { db.symptomDao().getSymptomsByPatientId(patientId) }
                latest.size == 1
            }
        } catch (error: Throwable) {
            throw AssertionError(
                "Timed out waiting for the patient-created symptom row; count=${latest.size}",
                error
            )
        }
        latest.single()
    }

    private fun waitForAssessment(
        patientId: Int,
        timeoutMillis: Long = 30_000
    ): AssessmentEntity {
        var latest: AssessmentEntity? = null
        try {
            composeRule.waitUntil(timeoutMillis) {
                latest = runBlocking { db.assessmentDao().getByPatientId(patientId).firstOrNull() }
                latest != null
            }
        } catch (error: Throwable) {
            throw AssertionError("Timed out waiting for persisted patient assessment", error)
        }
        return latest ?: throw AssertionError("Persisted assessment disappeared")
    }

    private fun waitForWorkflow(
        assessmentId: Int,
        expectedStatus: String,
        timeoutMillis: Long = 30_000
    ): AssessmentEntity {
        var latest: AssessmentEntity? = null
        try {
            composeRule.waitUntil(timeoutMillis) {
                latest = runBlocking { db.assessmentDao().getById(assessmentId) }
                latest?.workflowStatus == expectedStatus
            }
        } catch (error: Throwable) {
            throw AssertionError(
                "Timed out waiting for assessment #$assessmentId workflow '$expectedStatus'; " +
                    "actual='${latest?.workflowStatus}', attention='${latest?.needsDoctorAttention}'",
                error
            )
        }
        return latest ?: throw AssertionError("Assessment #$assessmentId disappeared")
    }

    private fun clickExactText(text: String, scrollTo: Boolean = false) {
        val nodes = composeRule.onAllNodes(hasText(text) and hasClickAction())
        val count = nodes.fetchSemanticsNodes().size
        check(count > 0) { "No clickable node found with exact text '$text'" }
        val node = nodes[count - 1]
        if (scrollTo && !node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }

    private fun replaceEditableField(
        index: Int,
        value: String,
        scrollTo: Boolean = false
    ) {
        val node = composeRule.onAllNodes(hasSetTextAction())[index]
        if (scrollTo && !node.isDisplayed()) node.performScrollTo()
        node.performTextReplacement(value)
    }

    private fun waitForText(
        text: String,
        stage: String,
        timeoutMillis: Long = 15_000
    ) {
        try {
            composeRule.waitUntil(timeoutMillis) { hasExactText(text) }
        } catch (error: Throwable) {
            throw AssertionError("Timed out during $stage waiting for exact text '$text'", error)
        }
    }

    private fun hasExactText(text: String): Boolean = runCatching {
        composeRule.onAllNodesWithText(text, substring = false)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }.getOrDefault(false)

    private fun waitForSeedRules() = runBlocking {
        repeat(100) {
            if (db.ruleDao().getAllRules().isNotEmpty()) return@runBlocking
            delay(50)
        }
        error("Initial rule seed did not complete")
    }

    private fun uniquePhone(offset: Int): String {
        val suffix = ((System.currentTimeMillis() + offset) % 1_000_000_000L)
            .toString()
            .padStart(9, '0')
        return "9$suffix"
    }
}
