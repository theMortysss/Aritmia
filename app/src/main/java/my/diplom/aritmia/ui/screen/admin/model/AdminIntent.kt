package my.diplom.aritmia.ui.screen.admin.model

import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.User

sealed class AdminScreenIntent {
    object LoadData : AdminScreenIntent()
    data class ChangeTab(val tabIndex: Int) : AdminScreenIntent()
    data class SelectUser(val user: User?) : AdminScreenIntent()
    data class SelectRule(val rule: RuleEntity?) : AdminScreenIntent()
    object ShowUserEditor : AdminScreenIntent()
    object HideUserEditor : AdminScreenIntent()
    object ShowRuleEditor : AdminScreenIntent()
    object HideRuleEditor : AdminScreenIntent()
    data class SaveUser(val user: User) : AdminScreenIntent()
    data class DeleteUser(val user: User) : AdminScreenIntent()
    data class SaveRule(val rule: RuleEntity) : AdminScreenIntent()
    data class DeleteRule(val rule: RuleEntity) : AdminScreenIntent()
    object Logout : AdminScreenIntent()
    data class UpdateFullName(val fullName: String) : AdminScreenIntent()
    data class UpdatePhone(val phone: String) : AdminScreenIntent()
    data class UpdatePassword(val password: String) : AdminScreenIntent()
    data class UpdateRole(val role: Role) : AdminScreenIntent()
    data class UpdateGender(val gender: String) : AdminScreenIntent()
    data class UpdateAge(val age: String) : AdminScreenIntent()
    data class UpdateSpecialty(val specialty: String) : AdminScreenIntent()
}