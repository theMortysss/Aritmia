package my.diplom.aritmia.ui.screen.admin.model

import androidx.compose.runtime.Immutable
import my.diplom.aritmia.data.AuditEventEntity
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.User

@Immutable
data class AdminScreenState(
    val users: List<User> = emptyList(),
    val rules: List<RuleEntity> = emptyList(),
    val auditEvents: List<AuditEventEntity> = emptyList(),
    val metrics: AdminDashboardMetrics = AdminDashboardMetrics(),
    val modelAvailable: Boolean = false,
    val modelVersion: String = "",
    val extractorVersion: String = "",
    val userSearch: String = "",
    val selectedUser: User? = null,
    val selectedRule: RuleEntity? = null,
    val showUserEditor: Boolean = false,
    val showRuleEditor: Boolean = false,
    val selectedTabIndex: Int = 0,
    val isLoading: Boolean = false,
    val logout: Boolean = false,
    val currentAdminId: Int = -1,
    val errorMessage: String? = null,
    val tempFullName: String = "",
    val tempPhone: String = "",
    val tempPassword: String = "",
    val tempRole: Role = Role.PATIENT,
    val tempGender: String = "",
    val tempAge: String = "",
    val tempSpecialty: String = ""
)

@Immutable
data class AdminDashboardMetrics(
    val users: Int = 0,
    val activeUsers: Int = 0,
    val patients: Int = 0,
    val doctors: Int = 0,
    val admins: Int = 0,
    val assessments: Int = 0,
    val ranked: Int = 0,
    val insufficientEvidence: Int = 0,
    val outOfScope: Int = 0,
    val modelUnavailable: Int = 0,
    val needsDoctorAttention: Int = 0,
    val newForDoctor: Int = 0,
    val contacted: Int = 0,
    val closed: Int = 0
)
