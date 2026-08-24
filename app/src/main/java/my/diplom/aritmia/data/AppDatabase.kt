package my.diplom.aritmia.data

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ── Пользователь ───────────────────────────────────────────────────────────────

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
    @TypeConverter fun toRole(role: String): Role   = Role.valueOf(role)
}

// ── Конвертер дат ──────────────────────────────────────────────────────────────

@RequiresApi(Build.VERSION_CODES.O)
object LocalDateTimeConverter {
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    @TypeConverter fun fromString(v: String?): LocalDateTime? = v?.let { LocalDateTime.parse(it, fmt) }
    @TypeConverter fun toString(dt: LocalDateTime?): String?  = dt?.format(fmt)
}

// ── Симптом / Диагноз ──────────────────────────────────────────────────────────

@Entity(
    foreignKeys = [ForeignKey(
        entity        = User::class,
        parentColumns = ["id"],
        childColumns  = ["patientId"],
        onDelete      = ForeignKey.CASCADE
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

// ── Правило ────────────────────────────────────────────────────────────────────

@Entity
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val symptomKey: String,
    val medicalTerm: String,
    val probabilityWeight: Int,
    val clarifyingQuestions: String?,
    val answerTriggers: String?
)

// ── DAO ────────────────────────────────────────────────────────────────────────

@Dao
interface UserDao {
    @Insert suspend fun insert(user: User)
    @Update suspend fun update(user: User)
    @Delete suspend fun delete(user: User)

    @Query("SELECT * FROM User") suspend fun getAllUsers(): List<User>
    @Query("SELECT * FROM User WHERE role = 'PATIENT'") suspend fun getAllPatients(): List<User>
    @Query("SELECT * FROM User WHERE role = 'DOCTOR'")  suspend fun getAllDoctors():  List<User>
    @Query("SELECT * FROM User WHERE role = 'ADMIN'")   suspend fun getAllAdmins():   List<User>

    @Query("SELECT * FROM User WHERE id = :id AND role = 'PATIENT' LIMIT 1")
    suspend fun getPatientById(id: Int): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND role = 'PATIENT' LIMIT 1")
    suspend fun getPatientByPhone(phone: String): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND role = :role LIMIT 1")
    suspend fun getUserByPhoneAndRole(phone: String, role: Role): User?

    // Legacy queries kept for compatibility with older code paths. New authentication
    // verifies password hashes in application code instead of querying by password.
    @Query("SELECT * FROM User WHERE phone = :phone AND password = :password AND role = 'PATIENT' LIMIT 1")
    suspend fun getPatientByPhoneAndPassword(phone: String, password: String): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND password = :password AND role = 'DOCTOR' LIMIT 1")
    suspend fun getDoctorByPhoneAndPassword(phone: String, password: String): User?

    @Query("SELECT * FROM User WHERE phone = :phone AND password = :password AND role = 'ADMIN' LIMIT 1")
    suspend fun getAdminByPhoneAndPassword(phone: String, password: String): User?
}

@Dao
interface SymptomDao {
    @Insert suspend fun insert(symptom: SymptomEntity)
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
interface RuleDao {
    @Insert  suspend fun insert(rule: RuleEntity)
    @Update  suspend fun update(rule: RuleEntity)
    @Delete  suspend fun delete(rule: RuleEntity)

    @Query("SELECT * FROM RuleEntity") fun getAllRulesFlow(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM RuleEntity") suspend fun getAllRules(): List<RuleEntity>
}

// ── База данных ────────────────────────────────────────────────────────────────

@RequiresApi(Build.VERSION_CODES.O)
@Database(
    entities     = [User::class, SymptomEntity::class, RuleEntity::class],
    version      = 7,
    exportSchema = false
)
@TypeConverters(RoleConverter::class, LocalDateTimeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao():    UserDao
    abstract fun symptomDao(): SymptomDao
    abstract fun ruleDao():    RuleDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE SymptomEntity ADD COLUMN nnProbability INTEGER")
            }
        }
    }
}
