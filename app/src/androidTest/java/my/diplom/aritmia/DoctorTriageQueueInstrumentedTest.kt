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
import my.diplom.aritmia.diagnosis.ComplaintTriageFlag
import my.diplom.aritmia.diagnosis.ComplaintTriageLevel
import my.diplom.aritmia.security.PasswordHasher
import my.diplom.aritmia.ui.screen.doctor.DoctorScreenViewModel
import my.diplom.aritmia.utils.formatPhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class DoctorTriageQueueInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val db
        get() = (context as AritmiaApp).appDatabase

    @Test
    fun doctorQueuePrioritizesStoredUrgencyAndDecodesHistoricalReason() {
        val patient = createPatient()
        val now = LocalDateTime.now()
        val ids = mutableListOf<Int>()

        try {
            val neutralId = createAssessment(
                patient = patient,
                complaints = "neutral ${System.currentTimeMillis()}",
                createdAt = now,
                triageLevel = "NONE",
                triageFlags = null
            )
            ids += neutralId

            val reviewFlag = ComplaintTriageFlag(
                id = "stored_review",
                level = ComplaintTriageLevel.MEDICAL_REVIEW,
                title = "Сохранённая причина плановой оценки",
                message = "Историческое сообщение для врача",
                matchedConceptIds = setOf("palpitations")
            )
            val reviewId = createAssessment(
                patient = patient,
                complaints = "review ${System.currentTimeMillis()}",
                createdAt = now.minusMinutes(1),
                triageLevel = "MEDICAL_REVIEW",
                triageFlags = AssessmentSnapshotCodec.encodeTriageFlags(listOf(reviewFlag))
            )
            ids += reviewId

            val emergencyFlag = ComplaintTriageFlag(
                id = "stored_emergency",
                level = ComplaintTriageLevel.EMERGENCY,
                title = "Сохранённая экстренная причина",
                message = "Сохранённое экстренное сообщение",
                matchedConceptIds = setOf("chest_pain")
            )
            val emergencyId = createAssessment(
                patient = patient,
                complaints = "emergency ${System.currentTimeMillis()}",
                createdAt = now.minusMinutes(2),
                triageLevel = "EMERGENCY",
                triageFlags = AssessmentSnapshotCodec.encodeTriageFlags(listOf(emergencyFlag))
            )
            ids += emergencyId

            val viewModel = DoctorScreenViewModel(db)
            waitUntil("triage assessments") {
                viewModel.state.value.assessments.count { it.assessment.id in ids } == ids.size
            }

            val relevant = viewModel.state.value.assessments.filter { it.assessment.id in ids }
            assertEquals(listOf(emergencyId, reviewId, neutralId), relevant.map { it.assessment.id })

            val emergencyItem = relevant.first()
            assertEquals("EMERGENCY", emergencyItem.assessment.triageLevel)
            assertEquals(1, emergencyItem.triageFlags.size)
            assertEquals("stored_emergency", emergencyItem.triageFlags.single().id)
            assertEquals("Сохранённая экстренная причина", emergencyItem.triageFlags.single().title)
            assertEquals("Сохранённое экстренное сообщение", emergencyItem.triageFlags.single().message)
            assertTrue("chest_pain" in emergencyItem.triageFlags.single().matchedConceptIds)
        } finally {
            runBlocking { db.userDao().delete(patient) }
        }
    }

    private fun createAssessment(
        patient: User,
        complaints: String,
        createdAt: LocalDateTime,
        triageLevel: String,
        triageFlags: String?
    ): Int = runBlocking {
        val sourceId = db.symptomDao().insert(
            SymptomEntity(
                userInput = complaints,
                medicalTerm = null,
                probability = 0,
                patientId = patient.id,
                clarifyingAnswers = null,
                createdAt = createdAt,
                nnProbability = null
            )
        ).toInt()

        db.assessmentDao().insert(
            AssessmentEntity(
                sourceSymptomId = sourceId,
                patientId = patient.id,
                complaints = complaints,
                status = "INSUFFICIENT_EVIDENCE",
                recognizedConceptIds = AssessmentSnapshotCodec.encodeConceptIds(emptySet()),
                modelCandidates = null,
                modelVersion = "doctor-triage-test",
                extractorVersion = "doctor-triage-test",
                createdAt = createdAt,
                workflowStatus = AssessmentWorkflow.NEW,
                needsDoctorAttention = true,
                triageLevel = triageLevel,
                triageFlags = triageFlags
            )
        ).toInt()
    }

    private fun createPatient(): User = runBlocking {
        val digits = "8" + (System.currentTimeMillis() % 1_000_000_000L)
            .toString()
            .padStart(9, '0')
            .takeLast(9)
        val phone = formatPhoneNumber(digits)
        db.userDao().insert(
            User(
                phone = phone,
                fullName = "Doctor Triage Queue Test",
                password = PasswordHasher.hash("patient123"),
                role = Role.PATIENT,
                age = 45,
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
