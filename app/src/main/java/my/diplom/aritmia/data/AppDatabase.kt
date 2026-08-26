package my.diplom.aritmia.data

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Entity
@TypeConverters(RoleConverter::class)
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val phone: String,
    val fullName: String,
    val password: String,
    val role: Role,
    val gender: String? = null,
    val age: Int? = null,
    val specialty: String? = null
)

enum class Role { PATIENT, DOCTOR, ADMIN }

object RoleConverter {
    @TypeConverter fun fromRole(role: Role): String = role.name
    @TypeConverter fun toRole(role: String): Role = Role.valueOf(role)
}

@RequiresApi(Build.VERSION_CODES.O)
object LocalDateTimeConverter {
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    @TypeConverter fun fromString(v: String?): LocalDateTime? = v?.let { LocalDateTime.parse(it, fmt) }
    @TypeConverter fun toString(dt: LocalDateTime?): String? = dt?.format(fmt)
}

@Entity(
    foreignKeys = [ForeignKey(
        entity = User::class,
        parentColumns = ["id"],
        childColumns = ["patientId"],
        onDelete = ForeignKey.CASCADE
    )]
)
@RequiresApi(Build.VERSION_CODES.O)
data class SymptomEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userInput: String,
    val medicalTerm: String?,
    val probability: Int,
    val patientId: Int,
    val clarifyingAnswers: String?,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val calledByDoctor: Boolean = false,
    val nnProbability: Int? = null
)

/**
 * Immutable diagnostic snapshot for one saved complaint submission.
 *
 * `complaints`, `recognizedConceptIds` and `modelCandidates` deliberately store the values that
 * were used/shown at assessment time. Later RuleEntity, extractor or model changes must not rewrite
 * historical assessments shown to a doctor.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SymptomEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceSymptomId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["patientId"]),
        Index(value = ["sourceSymptomId"], unique = true)
    ]
)
@RequiresApi(Build.VERSION_CODES.O)
data class AssessmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sourceSymptomId: Int,
    val patientId: Int,
    val complaints: String,
    val status: String,
    val recognizedConceptIds: String,
    val modelCandidates: String?,
    val modelVersion: String,
    val extractorVersion: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val workflowStatus: String = AssessmentWorkflow.NEW,
    val needsDoctorAttention: Boolean = true,
    val doctorNote: String? = null
)

object AssessmentWorkflow {
    const val NEW = "NEW"
    const val REVIEWED = "REVIEWED"
    const val CONTACT_REQUIRED = "CONTACT_REQUIRED"
    const val CONTACTED = "CONTACTED"
    const val CLOSED = "CLOSED"

    val values = setOf(NEW, REVIEWED, CONTACT_REQUIRED, CONTACTED, CLOSED)
}

/** Administrative mutation log. No foreign key is used intentionally so audit rows survive deletion. */
@Entity
@RequiresApi(Build.VERSION_CODES.O)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val adminId: Int?,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val details: String?,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

@Entity
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val symptomKey: String,
    val medicalTerm: String,
    val probabilityWeight: Int,
    val clarifyingQuestions: String?,
    val answerTriggers: String?
)

@Dao
interface UserDao {
    @Insert suspend fun insert(user: User)
    @Update suspend fun update(user: User)
    @Delete suspend fun delete(user: User)

    @Query("SELECT * FROM User") suspend fun getAllUsers(): List<User>
    @Query("SELECT * FROM User WHERE role = 'PATIENT'") suspend fun getAllPatients(): List<User>
    @Query("SELECT * FROM User WHERE role = 'DOCTOR'") suspend fun getAllDoctors(): List<User>
    @Query("SELECT * FROM User WHERE role = 'ADMIN'") suspend fun getAllAdmins(): List<User>

    @Query("SELECT * FROM User WHERE id = :id AND role = 'PATIENT' LIMIT 1")
    suspend fun getPatientById(id: Int): User?

    @Query("SELECT * FROM User WHERE id = :id AND role = :role LIMIT 1")
    suspend fun getUserByIdAndRole(id: Int, role: Role): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND role = 'PATIENT' LIMIT 1")
    suspend fun getPatientByPhone(phone: String): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND role = :role LIMIT 1")
    suspend fun getUserByPhoneAndRole(phone: String, role: Role): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND password = :password AND role = 'PATIENT' LIMIT 1")
    suspend fun getPatientByPhoneAndPassword(phone: String, password: String): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND password = :password AND role = 'DOCTOR' LIMIT 1")
    suspend fun getDoctorByPhoneAndPassword(phone: String, password: String): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND password = :password AND role = 'ADMIN' LIMIT 1")
    suspend fun getAdminByPhoneAndPassword(phone: String, password: String): User?
}

@Dao
interface SymptomDao {
    @Insert suspend fun insert(symptom: SymptomEntity): Long
    @Update suspend fun update(symptom: SymptomEntity)

    @Query("SELECT * FROM SymptomEntity")
    suspend fun getAllSymptoms(): List<SymptomEntity>

    @Query("""
        SELECT SymptomEntity.*
        FROM SymptomEntity
        JOIN User ON SymptomEntity.patientId = User.id
        WHERE ((:phoneFilter = '' OR REPLACE(REPLACE(User.phone, '+7-', ''), '-', '') LIKE '%' || :phoneFilter || '%')
        AND (:nameFilter = '' OR LOWER(User.fullName) LIKE '%' || LOWER(:nameFilter) || '%')
        AND (:startDate IS NULL OR SymptomEntity.createdAt >= :startDate)
        AND (:endDate IS NULL OR SymptomEntity.createdAt <= :endDate))
        AND SymptomEntity.probability >= :minProbability
        AND User.role = 'PATIENT'
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSymptomsFiltered(
        phoneFilter: String, nameFilter: String, minProbability: Int,
        startDate: String?, endDate: String?, limit: Int, offset: Int
    ): List<SymptomEntity>

    @Query("""
        SELECT COUNT(*)
        FROM SymptomEntity
        JOIN User ON SymptomEntity.patientId = User.id
        WHERE ((:phoneFilter = '' OR REPLACE(REPLACE(User.phone, '+7-', ''), '-', '') LIKE '%' || :phoneFilter || '%')
        AND (:nameFilter = '' OR LOWER(User.fullName) LIKE '%' || LOWER(:nameFilter) || '%')
        AND (:startDate IS NULL OR SymptomEntity.createdAt >= :startDate)
        AND (:endDate IS NULL OR SymptomEntity.createdAt <= :endDate))
        AND SymptomEntity.probability >= :minProbability
        AND User.role = 'PATIENT'
    """)
    suspend fun getFilteredCount(
        phoneFilter: String, nameFilter: String, minProbability: Int,
        startDate: String?, endDate: String?
    ): Int

    @Query("SELECT * FROM SymptomEntity WHERE patientId = :patientId")
    suspend fun getSymptomsByPatientId(patientId: Int): List<SymptomEntity>

    @Query("UPDATE SymptomEntity SET calledByDoctor = :called WHERE id = :symptomId")
    suspend fun updateCalledByDoctor(symptomId: Int, called: Boolean)
}

@Dao
interface AssessmentDao {
    @Insert suspend fun insert(assessment: AssessmentEntity): Long
    @Update suspend fun update(assessment: AssessmentEntity)

    @Query("SELECT * FROM AssessmentEntity WHERE sourceSymptomId = :sourceSymptomId LIMIT 1")
    suspend fun getBySourceSymptomId(sourceSymptomId: Int): AssessmentEntity?

    @Query("SELECT * FROM AssessmentEntity WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): AssessmentEntity?

    @Query("SELECT * FROM AssessmentEntity ORDER BY createdAt DESC")
    suspend fun getAll(): List<AssessmentEntity>

    @Query("SELECT * FROM AssessmentEntity ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AssessmentEntity>>

    @Query("SELECT * FROM AssessmentEntity WHERE patientId = :patientId ORDER BY createdAt DESC")
    suspend fun getByPatientId(patientId: Int): List<AssessmentEntity>

    @Query("""
        UPDATE AssessmentEntity
        SET workflowStatus = :workflowStatus,
            doctorNote = :doctorNote,
            needsDoctorAttention = :needsDoctorAttention
        WHERE id = :assessmentId
    """)
    suspend fun updateWorkflow(
        assessmentId: Int,
        workflowStatus: String,
        doctorNote: String?,
        needsDoctorAttention: Boolean
    )
}

@Dao
interface AuditEventDao {
    @Insert suspend fun insert(event: AuditEventEntity): Long

    @Query("SELECT * FROM AuditEventEntity ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AuditEventEntity>
}

@Dao
interface RuleDao {
    @Insert suspend fun insert(rule: RuleEntity)
    @Update suspend fun update(rule: RuleEntity)
    @Delete suspend fun delete(rule: RuleEntity)

    @Query("SELECT * FROM RuleEntity") fun getAllRulesFlow(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM RuleEntity") suspend fun getAllRules(): List<RuleEntity>
}

@RequiresApi(Build.VERSION_CODES.O)
@Database(
    entities = [
        User::class,
        SymptomEntity::class,
        AssessmentEntity::class,
        AuditEventEntity::class,
        RuleEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(RoleConverter::class, LocalDateTimeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun symptomDao(): SymptomDao
    abstract fun assessmentDao(): AssessmentDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun ruleDao(): RuleDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE SymptomEntity ADD COLUMN nnProbability INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS AssessmentEntity (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceSymptomId INTEGER NOT NULL,
                        patientId INTEGER NOT NULL,
                        complaints TEXT NOT NULL,
                        status TEXT NOT NULL,
                        recognizedConceptIds TEXT NOT NULL,
                        modelCandidates TEXT,
                        modelVersion TEXT NOT NULL,
                        extractorVersion TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        workflowStatus TEXT NOT NULL,
                        needsDoctorAttention INTEGER NOT NULL,
                        doctorNote TEXT,
                        FOREIGN KEY(patientId) REFERENCES User(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sourceSymptomId) REFERENCES SymptomEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_AssessmentEntity_patientId ON AssessmentEntity(patientId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_AssessmentEntity_sourceSymptomId ON AssessmentEntity(sourceSymptomId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS AuditEventEntity (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        adminId INTEGER,
                        action TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        entityId TEXT,
                        details TEXT,
                        createdAt TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
