package my.diplom.aritmia.ui.screen.admin

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
import my.diplom.aritmia.ui.screen.admin.model.AdminScreenIntent
import my.diplom.aritmia.ui.screen.admin.model.AdminScreenState
import my.diplom.aritmia.utils.formatPhoneNumber
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class AdminScreenViewModel @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(AdminScreenState())
    val state: StateFlow<AdminScreenState> = _state.asStateFlow()

    init {
        val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val adminId = sharedPreferences.getInt("current_admin_id", -1)
        _state.update { it.copy(currentAdminId = adminId) }
        onIntent(AdminScreenIntent.LoadData)
    }

    fun onIntent(intent: AdminScreenIntent) {
        when (intent) {
            is AdminScreenIntent.LoadData -> {
                viewModelScope.launch {
                    _state.update { it.copy(isLoading = true) }
                    val users = db.userDao().getAllUsers()
                    val rules = db.ruleDao().getAllRules()
                    _state.update {
                        it.copy(
                            users = users,
                            rules = rules,
                            isLoading = false
                        )
                    }
                }
            }
            is AdminScreenIntent.ChangeTab -> {
                _state.update { it.copy(selectedTabIndex = intent.tabIndex) }
            }
            is AdminScreenIntent.SelectUser -> {
                _state.update {
                    it.copy(
                        selectedUser = intent.user,
                        tempFullName = intent.user?.fullName ?: "",
                        tempPhone = intent.user?.phone?.filter { char -> char.isDigit() }?.takeLast(10) ?: "",
                        tempPassword = intent.user?.password ?: "",
                        tempRole = intent.user?.role ?: Role.PATIENT,
                        tempGender = intent.user?.gender ?: "",
                        tempAge = intent.user?.age?.toString() ?: "",
                        tempSpecialty = intent.user?.specialty ?: "",
                        errorMessage = null
                    )
                }
            }
            is AdminScreenIntent.SelectRule -> {
                _state.update { it.copy(selectedRule = intent.rule) }
            }
            is AdminScreenIntent.ShowUserEditor -> {
                _state.update { it.copy(showUserEditor = true) }
            }
            is AdminScreenIntent.HideUserEditor -> {
                _state.update { it.copy(showUserEditor = false, errorMessage = null) }
            }
            is AdminScreenIntent.ShowRuleEditor -> {
                _state.update { it.copy(showRuleEditor = true) }
            }
            is AdminScreenIntent.HideRuleEditor -> {
                _state.update { it.copy(showRuleEditor = false) }
            }
            is AdminScreenIntent.UpdateFullName -> {
                val filtered = intent.fullName.replace(Regex("[^а-яА-Яa-zA-Z\\s-]"), "")
                val formatted = filtered.split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar { it.uppercase() }
                    }
                val endsWithSpace = intent.fullName.endsWith(" ")
                val finalFullName = if (endsWithSpace) "$formatted " else formatted
                _state.update { it.copy(tempFullName = finalFullName, errorMessage = null) }
            }
            is AdminScreenIntent.UpdatePhone -> {
                val digits = intent.phone.filter { it.isDigit() }.take(10)
                _state.update { it.copy(tempPhone = digits, errorMessage = null) }
            }
            is AdminScreenIntent.UpdatePassword -> {
                _state.update { it.copy(tempPassword = intent.password, errorMessage = null) }
            }
            is AdminScreenIntent.UpdateRole -> {
                _state.update { it.copy(tempRole = intent.role, errorMessage = null) }
            }
            is AdminScreenIntent.UpdateGender -> {
                _state.update { it.copy(tempGender = intent.gender, errorMessage = null) }
            }
            is AdminScreenIntent.UpdateAge -> {
                val filteredAge = intent.age.filter { it.isDigit() }
                _state.update { it.copy(tempAge = filteredAge, errorMessage = null) }
            }
            is AdminScreenIntent.UpdateSpecialty -> {
                _state.update { it.copy(tempSpecialty = intent.specialty, errorMessage = null) }
            }
            is AdminScreenIntent.SaveUser -> {
                val fullName = _state.value.tempFullName.trim()
                val phone = _state.value.tempPhone
                val password = _state.value.tempPassword
                val role = _state.value.tempRole
                val gender = _state.value.tempGender
                val age = _state.value.tempAge
                val specialty = _state.value.tempSpecialty

                if (!fullName.matches(Regex("^[А-ЯA-Z][а-яa-z]+([\\s-][А-ЯA-Z][а-яa-z]+)*\$"))) {
                    _state.update { it.copy(errorMessage = "ФИО должно содержать только буквы, слова с заглавной буквы") }
                    return
                }
                if (phone.length != 10) {
                    _state.update { it.copy(errorMessage = "Телефон должен содержать ровно 10 цифр") }
                    return
                }
                val formattedPhone = formatPhoneNumber(phone)
                if (role == Role.PATIENT && age.isNotBlank() && (age.toIntOrNull() ?: 0) !in 1..150) {
                    _state.update { it.copy(errorMessage = "Возраст должен быть от 1 до 150") }
                    return
                }
                if (password.length < 6) {
                    _state.update { it.copy(errorMessage = "Пароль должен содержать минимум 6 символов") }
                    return
                }

                viewModelScope.launch {
                    val existingUserWithSameRole = db.userDao().getAllUsers().find {
                        it.phone == formattedPhone && it.role == role && it.id != intent.user.id
                    }
                    if (existingUserWithSameRole != null) {
                        _state.update { it.copy(errorMessage = "Пользователь с таким номером телефона и ролью уже существует") }
                        return@launch
                    }

                    val updatedUser = User(
                        id = intent.user.id,
                        fullName = fullName,
                        phone = formattedPhone,
                        password = password,
                        role = role,
                        gender = if (gender.isBlank()) null else gender,
                        age = age.toIntOrNull(),
                        specialty = if (specialty.isBlank()) null else specialty
                    )
                    if (updatedUser.id == 0) {
                        db.userDao().insert(updatedUser)
                    } else {
                        db.userDao().update(updatedUser)
                    }
                    onIntent(AdminScreenIntent.LoadData)
                }
            }
            is AdminScreenIntent.DeleteUser -> {
                viewModelScope.launch {
                    db.userDao().delete(intent.user)
                    onIntent(AdminScreenIntent.LoadData)
                }
            }
            is AdminScreenIntent.SaveRule -> {
                viewModelScope.launch {
                    if (intent.rule.id == 0) {
                        db.ruleDao().insert(intent.rule)
                    } else {
                        db.ruleDao().update(intent.rule)
                    }
                    onIntent(AdminScreenIntent.LoadData)
                }
            }
            is AdminScreenIntent.DeleteRule -> {
                viewModelScope.launch {
                    db.ruleDao().delete(intent.rule)
                    onIntent(AdminScreenIntent.LoadData)
                }
            }
            is AdminScreenIntent.Logout -> {
                _state.update { it.copy(logout = true) }
            }
        }
    }
}