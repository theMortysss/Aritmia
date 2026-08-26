package my.diplom.aritmia

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.diplom.aritmia.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssessmentMigrationInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val dbName = "assessment-migration-test.db"
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE User (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL(
                            "CREATE TABLE SymptomEntity (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, patientId INTEGER NOT NULL)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration7To8CreatesVersionedAssessmentAndAuditTables() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_7_8.migrate(db)

        assertTrue(tableExists(db, "AssessmentEntity"))
        assertTrue(tableExists(db, "AuditEventEntity"))

        val assessmentColumns = columns(db, "AssessmentEntity")
        assertTrue("status" in assessmentColumns)
        assertTrue("recognizedConceptIds" in assessmentColumns)
        assertTrue("modelCandidates" in assessmentColumns)
        assertTrue("modelVersion" in assessmentColumns)
        assertTrue("extractorVersion" in assessmentColumns)
        assertTrue("workflowStatus" in assessmentColumns)
        assertTrue("needsDoctorAttention" in assessmentColumns)
        assertTrue("doctorNote" in assessmentColumns)

        db.query("PRAGMA index_list('AssessmentEntity')").use { cursor ->
            var foundUniqueSourceIndex = false
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                if (name == "index_AssessmentEntity_sourceSymptomId") {
                    assertEquals(1, unique)
                    foundUniqueSourceIndex = true
                }
            }
            assertTrue(foundUniqueSourceIndex)
        }
    }

    @Test
    fun migration8To9AddsActiveFlagAndKeepsExistingUsersActive() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_7_8.migrate(db)
        db.execSQL("INSERT INTO User(id) VALUES (1)")

        AppDatabase.MIGRATION_8_9.migrate(db)

        assertTrue("isActive" in columns(db, "User"))
        db.query("SELECT isActive FROM User WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("isActive")))
        }
    }

    @Test
    fun migration9To10IndexesSymptomPatientForeignKey() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_7_8.migrate(db)
        AppDatabase.MIGRATION_8_9.migrate(db)
        AppDatabase.MIGRATION_9_10.migrate(db)

        db.query("PRAGMA index_list('SymptomEntity')").use { cursor ->
            var foundPatientIndex = false
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                if (name == "index_SymptomEntity_patientId") {
                    assertEquals(0, unique)
                    foundPatientIndex = true
                }
            }
            assertTrue(foundPatientIndex)
        }
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info('$table')").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }
}