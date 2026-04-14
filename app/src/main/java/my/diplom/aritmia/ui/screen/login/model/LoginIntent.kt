package my.diplom.aritmia.ui.screen.login.model

import my.diplom.aritmia.data.Role

sealed class LoginScreenIntent {
    data class ChangeTab(val tabIndex: Int) : LoginScreenIntent()
    data class UpdateFullName(val fullName: String) : LoginScreenIntent()
    data class UpdatePhone(val phone: String) : LoginScreenIntent()
    data class UpdateGender(val gender: String) : LoginScreenIntent()
    data class UpdateAge(val age: String) : LoginScreenIntent()
    data class UpdateSpecialty(val specialty: String) : LoginScreenIntent()
    data class UpdatePassword(val password: String) : LoginScreenIntent()
    data class UpdateRole(val role: Role) : LoginScreenIntent()
    object Login : LoginScreenIntent()
    object Register : LoginScreenIntent()
}