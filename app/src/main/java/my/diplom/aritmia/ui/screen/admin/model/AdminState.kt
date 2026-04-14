package my.diplom.aritmia.ui.screen.admin.model

import androidx.compose.runtime.Immutable
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.User

@Immutable
data class AdminScreenState(
    val users: List<User> = emptyList(),
    val rules: List<RuleEntity> = emptyList(),
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

