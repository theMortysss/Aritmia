package my.diplom.aritmia.ui.screen.login.model

import androidx.compose.runtime.Immutable
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.User

@Immutable
data class LoginScreenState(
    val selectedTabIndex: Int = 0,
    val fullName: String = "",
    val phone: String = "",
    val gender: String = "",
    val age: String = "",
    val specialty: String = "",
    val password: String = "",
    val role: Role = Role.PATIENT,
    val errorMessage: String? = null,
    val loginSuccess: User? = null
)
