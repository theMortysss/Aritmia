package my.diplom.aritmia

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.diplom.aritmia.app.AritmiaApp
import my.diplom.aritmia.data.AssessmentEntity
import my.diplom.aritmia.data.AssessmentSnapshotCodec
import my.diplom.aritmia.data.AssessmentWorkflow
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.SymptomEntity
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
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class AdminDoctorJourneyInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    private val db
        get() = (context as AritmiaApp).appDatabase

    @After
    fun clearSession() {
        prefs.edit().clear().commit()
    }

    @Test
    fun adminCanBlockUserAuditChangeAndBlockedSessionCannotReturn() {
        clearSession()
        val adminPhone = uniquePhone(1)
        val patientPhone = uniquePhone(2)
        val adminPassword = "admin123"
        val patientPassword = "patient123"
        val patientName = "Блок Тест"

        val admin = createUser(
            phoneDigits = adminPhone,
            fullName = "Админ Тест",
            password = adminPassword,
            role = Role.ADMIN
        )
        val patient = createUser(
            phoneDigits = patientPhone,
            fullName = patientName,
            password = patientPassword,
            role = Role.PATIENT,
            age = 36
        )

        prefs.edit().putInt("current_admin_id", admin.id).commit()

        var scenario: ActivityScenario<MainActivity>? = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForText("Админ панель", "admin control center")
            composeRule.onNodeWithText("Пользователи").performClick()
            waitForText("Добавить пользователя", "users tab")

            replaceEditableField(0, patientName)
            waitForText(patientName, "filtered patient")
            assertTrue(
                "Admin list must never expose stored password hashes",
                composeRule.onAllNodesWithText("Пароль:", substring = true)
                    .fetchSemanticsNodes().isEmpty()
            )

            composeRule.onNodeWithText("Заблокировать").performClick()
            waitForText("Статус: заблокирован", "blocked user state")

            val blocked = runBlocking {
                db.userDao().getUserByPhoneAndRole(formatPhoneNumber(patientPhone), Role.PATIENT)
            }
            assertFalse(blocked?.isActive ?: true)
            assertTrue(
                "Blocking a user must be auditable",
                runBlocking { db.auditEventDao().getRecent(100) }
                    .any { it.action == "USER_BLOCK" && it.entityId == patient.id.toString() }
            )

            composeRule.onNodeWithText("Правила").performClick()
            waitForText("Добавить правило", "admin rules tab")
            assertTrue(
                "Legacy rule weight editor must not be available to admin",
                composeRule.onAllNodesWithText("Вес правила (0–100)")
                    .fetchSemanticsNodes().isEmpty()
            )

            scenario!!.close()
            scenario = null

            // A blocked persisted account must not be restored into the patient area.
            prefs.edit().clear().putInt("current_patient_id", patient.id).commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitForText("Вход в приложение", "blocked persisted session invalidation", 20_000)
            assertEquals(-1, prefs.getInt("current_patient_id", -1))

            replaceEditableField(0, patientPhone)
            replaceEditableField(1, patientPassword)
            composeRule.onNodeWithText("Войти").performClick()
            waitForText(
                "Учётная запись заблокирована администратором",
                "blocked patient login"
            )
        } finally {
            scenario?.close()
        }
    }

    @Test
    fun doctorCanReviewImmutableAssessmentAndSaveWorkflowNote() {
        clearSession()
        val doctorPhone = uniquePhone(3)
        val patientPhone = uniquePhone(4)
        val patientName = "Пациент Врача"
        val doctor = createUser(
            phoneDigits = doctorPhone,
            fullName = "Врач Тест",
            password = "doctor123",
            role = Role.DOCTOR,
            specialty = "Кардиолог"
        )
        val patient = createUser(
            phoneDigits = patientPhone,
            fullName = patientName,
            password = "patient123",
            role = Role.PATIENT,
            age = 42
        )

        val assessment = runBlocking {
            val sourceId = db.symptomDao().insert(
                SymptomEntity(
                    userInput = "нерегулярный пульс. сердце стучит. кружится голова",
                    medicalTerm = "Нерегулярный пульс, Ощущение сердцебиения, Головокружение",
                    probability = 0,
                    patientId = patient.id,
                    clarifyingAnswers = null,
                    createdAt = LocalDateTime.now(),
                    nnProbability = null
                )
            ).toInt()
            val id = db.assessmentDao().insert(
                AssessmentEntity(
                    sourceSymptomId = sourceId,
                    patientId = patient.id,
                    complaints = "нерегулярный пульс. сердце стучит. кружится голова",
                    status = "INSUFFICIENT_EVIDENCE",
                    recognizedConceptIds = AssessmentSnapshotCodec.encodeConceptIds(
                        setOf("irregular_rhythm", "palpitations", "dizziness")
                    ),
                    modelCandidates = null,
                    modelVersion = "v2",
                    extractorVersion = "russian-complaint-v3",
                    createdAt = LocalDateTime.now(),
                    workflowStatus = AssessmentWorkflow.NEW,
                    needsDoctorAttention = true
                )
            )
            db.assessmentDao().getById(id.toInt())!!
        }

        prefs.edit().putInt("current_doctor_id", doctor.id).commit()

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Меню врача", "doctor workspace")
            waitForText(patientName, "doctor assessment queue")
            composeRule.onAllNodesWithText("Открыть обращение")[0].performClick()
            waitForText("Обращение пациента", "assessment details")
            waitForText("История пациента", "patient assessment timeline")
            waitForText("Недостаточно данных", "frozen assessment status")

            replaceEditableField(0, "Пациенту рекомендована очная консультация")
            composeRule.onNodeWithText("Просмотрено")
                .performScrollTo()
                .performClick()
            waitForText("Текущий статус: Просмотрено", "saved doctor workflow")

            val updated = runBlocking { db.assessmentDao().getById(assessment.id) }!!
            assertEquals(AssessmentWorkflow.REVIEWED, updated.workflowStatus)
            assertEquals("Пациенту рекомендована очная консультация", updated.doctorNote)
            assertFalse(updated.needsDoctorAttention)
            assertEquals(assessment.complaints, updated.complaints)
            assertEquals(assessment.recognizedConceptIds, updated.recognizedConceptIds)
            assertEquals(assessment.modelVersion, updated.modelVersion)

            composeRule.onNodeWithText("Закрыть").performClick()
            composeRule.onNodeWithText("Правила").performClick()
            waitForText("Добавить правило", "doctor rules workspace")
            composeRule.onNodeWithText("Добавить правило").performClick()
            waitForText("Добавить правило", "doctor rule dialog")
            assertTrue(
                "Legacy rule weight editor must not be available to doctor",
                composeRule.onAllNodesWithText("Вес правила (0–100)")
                    .fetchSemanticsNodes().isEmpty()
            )
        }
    }

    private fun createUser(
        phoneDigits: String,
        fullName: String,
        password: String,
        role: Role,
        age: Int? = null,
        specialty: String? = null
    ): User = runBlocking {
        val formatted = formatPhoneNumber(phoneDigits)
        val existing = db.userDao().getUserByPhoneAndRole(formatted, role)
        if (existing != null) return@runBlocking existing
        db.userDao().insert(
            User(
                phone = formatted,
                fullName = fullName,
                password = PasswordHasher.hash(password),
                role = role,
                age = age,
                specialty = specialty,
                isActive = true
            )
        )
        db.userDao().getUserByPhoneAndRole(formatted, role)!!
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
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError("Timed out during $stage waiting for exact text '$text'", error)
        }
    }

    private fun uniquePhone(offset: Int): String {
        val base = (System.currentTimeMillis() % 100_000_000L) + offset
        return "9" + base.toString().padStart(9, '0').takeLast(9)
    }
}
