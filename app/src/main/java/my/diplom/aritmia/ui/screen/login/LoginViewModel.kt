package my.diplom.aritmia.ui.screen.login

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.User
import my.diplom.aritmia.ui.screen.login.model.LoginScreenIntent
import my.diplom.aritmia.ui.screen.login.model.LoginScreenState
import my.diplom.aritmia.utils.formatPhoneNumber
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(LoginScreenState())
    val state: StateFlow<LoginScreenState> = _state.asStateFlow()

    fun onIntent(intent: LoginScreenIntent) {
        when (intent) {
            is LoginScreenIntent.ChangeTab -> {
                _state.update { it.copy(selectedTabIndex = intent.tabIndex, errorMessage = null) }
            }
            is LoginScreenIntent.UpdateFullName -> {
                val filtered = intent.fullName.replace(Regex("[^а-яА-Яa-zA-Z\\s-]"), "")
                val formatted = filtered.split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar { it.uppercase() }
                    }
                val endsWithSpace = intent.fullName.endsWith(" ")
                val finalFullName = if (endsWithSpace) "$formatted " else formatted
                _state.update { it.copy(fullName = finalFullName, errorMessage = null) }
            }
            is LoginScreenIntent.UpdatePhone -> {
                val digits = intent.phone.filter { it.isDigit() }.take(10)
                _state.update { it.copy(phone = digits, errorMessage = null) }
            }
            is LoginScreenIntent.UpdateGender -> {
                _state.update { it.copy(gender = intent.gender, errorMessage = null) }
            }
            is LoginScreenIntent.UpdateAge -> {
                val filteredAge = intent.age.filter { it.isDigit() }
                _state.update { it.copy(age = filteredAge, errorMessage = null) }
            }
            is LoginScreenIntent.UpdateSpecialty -> {
                _state.update { it.copy(specialty = intent.specialty, errorMessage = null) }
            }
            is LoginScreenIntent.UpdatePassword -> {
                _state.update { it.copy(password = intent.password, errorMessage = null) }
            }
            is LoginScreenIntent.UpdateRole -> {
                _state.update { it.copy(role = intent.role, errorMessage = null) }
            }
            is LoginScreenIntent.Login -> {
                val phone = _state.value.phone
                val password = _state.value.password
                val selectedRole = _state.value.role

                if (phone.length != 10) {
                    _state.update { it.copy(errorMessage = "Телефон должен содержать ровно 10 цифр") }
                    return
                }
                val formattedPhone = formatPhoneNumber(phone)
                if (password.length < 6) {
                    _state.update { it.copy(errorMessage = "Пароль должен содержать минимум 6 символов") }
                    return
                }

                viewModelScope.launch {
                    val adminUser = db.userDao().getAdminByPhoneAndPassword(formattedPhone, password)
                    if (adminUser != null) {
                        val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        with(sharedPreferences.edit()) {
                            putInt("current_admin_id", adminUser.id)
                            apply()
                        }
                        _state.update { it.copy(loginSuccess = adminUser, errorMessage = null) }
                        return@launch
                    }

                    val user = when (selectedRole) {
                        Role.PATIENT -> db.userDao().getPatientByPhoneAndPassword(formattedPhone, password)
                        Role.DOCTOR -> db.userDao().getDoctorByPhoneAndPassword(formattedPhone, password)
                        else -> null
                    }
                    if (user != null) {
                        val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        with(sharedPreferences.edit()) {
                            when (selectedRole) {
                                Role.PATIENT -> putInt("current_patient_id", user.id)
                                Role.DOCTOR -> putInt("current_doctor_id", user.id)
                                else -> { }
                            }
                            apply()
                        }
                        _state.update { it.copy(loginSuccess = user, errorMessage = null) }
                    } else {
                        _state.update { it.copy(errorMessage = "Неверный телефон или пароль") }
                    }
                }
            }
            is LoginScreenIntent.Register -> {
                val fullName = _state.value.fullName.trim()
                val phone = _state.value.phone
                val age = _state.value.age
                val password = _state.value.password
                val role = _state.value.role

                if (role == Role.ADMIN) {
                    _state.update { it.copy(errorMessage = "Регистрация администраторов запрещена") }
                    return
                }

                if (role == Role.DOCTOR) {
                    _state.update { it.copy(errorMessage = "Регистрация врачей запрещена") }
                    return
                }

                if (!fullName.matches(Regex("^[А-ЯA-Z][а-яa-z]+([\\s-][А-ЯA-Z][а-яa-z]+)*\$"))) {
                    _state.update { it.copy(errorMessage = "ФИО должно содержать только буквы, слова с заглавной буквы") }
                    return
                }
                if (phone.length != 10) {
                    _state.update { it.copy(errorMessage = "Телефон должен содержать ровно 10 цифр") }
                    return
                }
                val formattedPhone = formatPhoneNumber(phone)
                if (role == Role.PATIENT && (age.toIntOrNull() ?: 0) !in 1..150) {
                    _state.update { it.copy(errorMessage = "Возраст должен быть от 1 до 150") }
                    return
                }
                if (password.length < 6) {
                    _state.update { it.copy(errorMessage = "Пароль должен содержать минимум 6 символов") }
                    return
                }

                viewModelScope.launch {
                    val existingUserWithSameRole = db.userDao().getAllUsers().find {
                        it.phone == formattedPhone && it.role == role
                    }
                    if (existingUserWithSameRole != null) {
                        _state.update { it.copy(errorMessage = "Пользователь с таким номером и ролью уже существует") }
                        return@launch
                    }

                    val newUser = User(
                        phone = formattedPhone,
                        fullName = fullName,
                        password = password,
                        role = role,
                        gender = if (role == Role.PATIENT) _state.value.gender else null,
                        age = if (role == Role.PATIENT) age.toIntOrNull() else null,
                        specialty = if (role == Role.DOCTOR) _state.value.specialty else null
                    )
                    db.userDao().insert(newUser)
                    val insertedUser = when (role) {
                        Role.PATIENT -> db.userDao().getPatientByPhone(formattedPhone)
                        Role.DOCTOR -> null
                        Role.ADMIN -> null
                    }
                    if (insertedUser != null) {
                        val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        with(sharedPreferences.edit()) {
                            when (role) {
                                Role.PATIENT -> putInt("current_patient_id", insertedUser.id)
                                Role.DOCTOR -> { }
                                Role.ADMIN -> { }
                            }
                            apply()
                        }
                        _state.update { it.copy(loginSuccess = insertedUser, errorMessage = null) }
                    }
                }
            }
        }
    }
}