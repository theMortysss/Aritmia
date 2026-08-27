package my.diplom.aritmia

import android.content.Context
import android.os.SystemClock
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
import my.diplom.aritmia.ui.screen.doctor.DoctorScreenViewModel
import my.diplom.aritmia.ui.screen.doctor.model.DoctorScreenIntent
import my.diplom.aritmia.utils.formatPhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class DoctorWorkflowViewModelInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val db
        get() = (context as AritmiaApp).appDatabase

    @Test
    fun viewModelPersistsWorkflowForSelectedAssessment() {
        val patient = createPatient()
        val complaints = "тестовое обращение врача ${System.currentTimeMillis()}"
        val assessment = runBlocking {
            val sourceId = db.symptomDao().insert(
                SymptomEntity(
                    userInput = complaints,
                    medicalTerm = "Тестовый термин",
                    probability = 0,
                    patientId = patient.id,
                    clarifyingAnswers = null,
                    createdAt = LocalDateTime.now(),
                    nnProbability = null
                )
            ).toInt()
            val assessmentId = db.assessmentDao().insert(
                AssessmentEntity(
                    sourceSymptomId = sourceId,
                    patientId = patient.id,
                    complaints = complaints,
                    status = "INSUFFICIENT_EVIDENCE",
                    recognizedConceptIds = AssessmentSnapshotCodec.encodeConceptIds(
                        setOf("palpitations")
                    ),
                    modelCandidates = null,
                    modelVersion = "viewmodel-test",
                    extractorVersion = "viewmodel-test",
                    createdAt = LocalDateTime.now(),
                    workflowStatus = AssessmentWorkflow.NEW,
                    needsDoctorAttention = true
                )
            ).toInt()
            db.assessmentDao().getById(assessmentId)!!
        }

        try {
            val viewModel = DoctorScreenViewModel(db)
            waitUntil("assessment list") {
                viewModel.state.value.assessments.any { it.assessment.id == assessment.id }
            }

            viewModel.onIntent(DoctorScreenIntent.OpenAssessment(assessment.id))
            waitUntil("selected assessment") {
                viewModel.state.value.selectedAssessment?.assessment?.id == assessment.id
            }

            val note = "Проверка сохранения workflow через ViewModel"
            viewModel.onIntent(DoctorScreenIntent.UpdateDoctorNote(note))
            viewModel.onIntent(
                DoctorScreenIntent.SaveAssessmentWorkflow(AssessmentWorkflow.REVIEWED)
            )

            waitUntil("persisted workflow") {
                runBlocking {
                    db.assessmentDao().getById(assessment.id)?.workflowStatus ==
                        AssessmentWorkflow.REVIEWED
                }
            }

            val updated = runBlocking { db.assessmentDao().getById(assessment.id) }!!
            assertEquals(AssessmentWorkflow.REVIEWED, updated.workflowStatus)
            assertEquals(note, updated.doctorNote)
            assertFalse(updated.needsDoctorAttention)
        } finally {
            runBlocking { db.userDao().delete(patient) }
        }
    }

    private fun createPatient(): User = runBlocking {
        val digits = "9" + (System.currentTimeMillis() % 1_000_000_000L)
            .toString()
            .padStart(9, '0')
            .takeLast(9)
        val phone = formatPhoneNumber(digits)
        db.userDao().insert(
            User(
                phone = phone,
                fullName = "VM Workflow Test",
                password = PasswordHasher.hash("patient123"),
                role = Role.PATIENT,
                age = 40,
                isActive = true
            )
        )
        db.userDao().getUserByPhoneAndRole(phone, Role.PATIENT)!!
    }

    private fun waitUntil(
        stage: String,
        timeoutMillis: Long = 15_000,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $stage")
    }
}
