package my.diplom.aritmia.ui.screen.admin

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.data.AssessmentWorkflow
import my.diplom.aritmia.data.AuditEventEntity
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.User
import my.diplom.aritmia.diagnosis.DiseaseNetworkRepository
import my.diplom.aritmia.security.PasswordHasher
import my.diplom.aritmia.ui.screen.admin.model.AdminDashboardMetrics
import my.diplom.aritmia.ui.screen.admin.model.AdminScreenIntent
import my.diplom.aritmia.ui.screen.admin.model.AdminScreenState
import my.diplom.aritmia.utils.formatPhoneNumber
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class AdminScreenViewModel @Inject constructor(
    private val db: AppDatabase,
    private val diseaseNetworkRepository: DiseaseNetworkRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(AdminScreenState())
    val state: StateFlow<AdminScreenState> = _state.asStateFlow()

    init {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        _state.update { it.copy(currentAdminId = prefs.getInt("current_admin_id", -1)) }
        onIntent(AdminScreenIntent.LoadData)
    }

    fun onIntent(intent: AdminScreenIntent) {
        when (intent) {
            is AdminScreenIntent.LoadData -> viewModelScope.launch { loadData() }
            is AdminScreenIntent.ChangeTab -> _state.update { it.copy(selectedTabIndex = intent.tabIndex) }
            is AdminScreenIntent.UpdateUserSearch -> _state.update { it.copy(userSearch = intent.query) }
            is AdminScreenIntent.SelectUser -> _state.update {
                it.copy(
                    selectedUser = intent.user,
                    tempFullName = intent.user?.fullName ?: "",
                    tempPhone = intent.user?.phone?.filter(Char::isDigit)?.takeLast(10) ?: "",
                    tempPassword = "",
                    tempRole = intent.user?.role ?: Role.PATIENT,
                    tempGender = intent.user?.gender ?: "",
                    tempAge = intent.user?.age?.toString() ?: "",
                    tempSpecialty = intent.user?.specialty ?: "",
                    errorMessage = null
                )
            }
            is AdminScreenIntent.SelectRule -> _state.update { it.copy(selectedRule = intent.rule) }
            is AdminScreenIntent.ShowUserEditor -> _state.update { it.copy(showUserEditor = true) }
            is AdminScreenIntent.HideUserEditor -> _state.update {
                it.copy(showUserEditor = false, selectedUser = null, errorMessage = null)
            }
            is AdminScreenIntent.ShowRuleEditor -> _state.update { it.copy(showRuleEditor = true) }
            is AdminScreenIntent.HideRuleEditor -> _state.update {
                it.copy(showRuleEditor = false, selectedRule = null)
            }
            is AdminScreenIntent.UpdateFullName -> updateFullName(intent.fullName)
            is AdminScreenIntent.UpdatePhone -> _state.update {
                it.copy(tempPhone = intent.phone.filter(Char::isDigit).take(10), errorMessage = null)
            }
            is AdminScreenIntent.UpdatePassword -> _state.update {
                it.copy(tempPassword = intent.password, errorMessage = null)
            }
            is AdminScreenIntent.UpdateRole -> _state.update { it.copy(tempRole = intent.role, errorMessage = null) }
            is AdminScreenIntent.UpdateGender -> _state.update { it.copy(tempGender = intent.gender, errorMessage = null) }
            is AdminScreenIntent.UpdateAge -> _state.update {
                it.copy(tempAge = intent.age.filter(Char::isDigit), errorMessage = null)
            }
            is AdminScreenIntent.UpdateSpecialty -> _state.update {
                it.copy(tempSpecialty = intent.specialty, errorMessage = null)
            }
            is AdminScreenIntent.SaveUser -> saveUser(intent.user)
            is AdminScreenIntent.DeleteUser -> deleteUser(intent.user)
            is AdminScreenIntent.SetUserActive -> setUserActive(intent.user, intent.active)
            is AdminScreenIntent.SaveRule -> saveRule(intent.rule)
            is AdminScreenIntent.DeleteRule -> deleteRule(intent.rule)
            is AdminScreenIntent.Logout -> _state.update { it.copy(logout = true) }
        }
    }

    private suspend fun loadData() {
        _state.update { it.copy(isLoading = true) }
        diseaseNetworkRepository.initialize()
        val users = db.userDao().getAllUsers()
        val rules = db.ruleDao().getAllRules()
        val assessments = db.assessmentDao().getAll()
        val audit = db.auditEventDao().getRecent(100)
        val metrics = AdminDashboardMetrics(
            users = users.size,
            activeUsers = users.count { it.isActive },
            patients = users.count { it.role == Role.PATIENT },
            doctors = users.count { it.role == Role.DOCTOR },
            admins = users.count { it.role == Role.ADMIN },
            assessments = assessments.size,
            ranked = assessments.count { it.status == "RANKED" },
            insufficientEvidence = assessments.count { it.status == "INSUFFICIENT_EVIDENCE" },
            outOfScope = assessments.count { it.status == "OUT_OF_SCOPE" },
            modelUnavailable = assessments.count { it.status == "MODEL_UNAVAILABLE" },
            needsDoctorAttention = assessments.count { it.needsDoctorAttention },
            newForDoctor = assessments.count { it.workflowStatus == AssessmentWorkflow.NEW },
            contacted = assessments.count { it.workflowStatus == AssessmentWorkflow.CONTACTED },
            closed = assessments.count { it.workflowStatus == AssessmentWorkflow.CLOSED }
        )
        _state.update {
            it.copy(
                users = users,
                rules = rules,
                auditEvents = audit,
                metrics = metrics,
                modelAvailable = diseaseNetworkRepository.isUsingPretrainedModel(),
                modelVersion = DiseaseNetworkRepository.MODEL_VERSION,
                extractorVersion = DiseaseNetworkRepository.EXTRACTOR_VERSION,
                isLoading = false
            )
        }
    }

    private fun updateFullName(input: String) {
        val filtered = input.replace(Regex("[^а-яА-Яa-zA-Z\\s-]"), "")
        val formatted = filtered.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
        val finalName = if (input.endsWith(" ")) "$formatted " else formatted
        _state.update { it.copy(tempFullName = finalName, errorMessage = null) }
    }

    private fun saveUser(formUser: User) {
        val fullName = _state.value.tempFullName.trim()
        val phone = _state.value.tempPhone
        val password = _state.value.tempPassword
        val role = _state.value.tempRole
        val gender = _state.value.tempGender
        val age = _state.value.tempAge
        val specialty = _state.value.tempSpecialty
        val currentAdminId = _state.value.currentAdminId
        val original = _state.value.selectedUser?.takeIf { it.id == formUser.id }

        if (!fullName.matches(Regex("^[А-ЯA-Z][а-яa-z]+([\\s-][А-ЯA-Z][а-яa-z]+)*$"))) {
            setError("ФИО должно содержать только буквы, слова с заглавной буквы"); return
        }
        if (phone.length != 10) {
            setError("Телефон должен содержать ровно 10 цифр"); return
        }
        if (role == Role.PATIENT && age.isNotBlank() && (age.toIntOrNull() ?: 0) !in 1..150) {
            setError("Возраст должен быть от 1 до 150"); return
        }
        if (formUser.id == 0 && password.length < 6) {
            setError("Для нового пользователя пароль должен содержать минимум 6 символов"); return
        }
        if (formUser.id != 0 && password.isNotBlank() && password.length < 6) {
            setError("Новый пароль должен содержать минимум 6 символов"); return
        }
        if (formUser.id == currentAdminId && role != Role.ADMIN) {
            setError("Нельзя снять роль администратора с текущей учётной записи"); return
        }

        val formattedPhone = formatPhoneNumber(phone)
        viewModelScope.launch {
            val duplicate = db.userDao().getUserByPhoneAndRole(formattedPhone, role)
            if (duplicate != null && duplicate.id != formUser.id) {
                setError("Пользователь с таким номером телефона и ролью уже существует")
                return@launch
            }

            val storedPassword = when {
                password.isNotBlank() -> withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
                original != null -> original.password
                else -> return@launch
            }
            val updated = User(
                id = formUser.id,
                fullName = fullName,
                phone = formattedPhone,
                password = storedPassword,
                role = role,
                gender = if (role == Role.PATIENT) gender.ifBlank { null } else null,
                age = if (role == Role.PATIENT) age.toIntOrNull() else null,
                specialty = if (role == Role.DOCTOR) specialty.ifBlank { null } else null,
                isActive = original?.isActive ?: true
            )

            if (updated.id == 0) db.userDao().insert(updated) else db.userDao().update(updated)
            val persisted = db.userDao().getUserByPhoneAndRole(formattedPhone, role)
            logAudit(
                action = if (formUser.id == 0) "USER_CREATE" else "USER_UPDATE",
                entityType = "User",
                entityId = persisted?.id?.toString() ?: formUser.id.takeIf { it != 0 }?.toString(),
                details = "role=${role.name};passwordChanged=${password.isNotBlank()}"
            )
            _state.update { it.copy(showUserEditor = false, selectedUser = null, errorMessage = null) }
            loadData()
        }
    }

    private fun deleteUser(user: User) {
        if (user.id == _state.value.currentAdminId) {
            setError("Нельзя удалить текущую учётную запись администратора")
            return
        }
        viewModelScope.launch {
            db.userDao().delete(user)
            logAudit("USER_DELETE", "User", user.id.toString(), "role=${user.role.name}")
            loadData()
        }
    }

    private fun setUserActive(user: User, active: Boolean) {
        if (user.id == _state.value.currentAdminId && !active) {
            setError("Нельзя заблокировать текущую учётную запись администратора")
            return
        }
        viewModelScope.launch {
            db.userDao().update(user.copy(isActive = active))
            logAudit(
                action = if (active) "USER_UNBLOCK" else "USER_BLOCK",
                entityType = "User",
                entityId = user.id.toString(),
                details = "role=${user.role.name}"
            )
            loadData()
        }
    }

    private fun saveRule(rule: RuleEntity) {
        viewModelScope.launch {
            val creating = rule.id == 0
            if (creating) db.ruleDao().insert(rule) else db.ruleDao().update(rule)
            val persisted = if (creating) {
                db.ruleDao().getAllRules()
                    .filter { it.symptomKey == rule.symptomKey && it.medicalTerm == rule.medicalTerm }
                    .maxByOrNull { it.id }
            } else rule
            logAudit(
                action = if (creating) "RULE_CREATE" else "RULE_UPDATE",
                entityType = "RuleEntity",
                entityId = persisted?.id?.toString(),
                details = "symptomKey=${rule.symptomKey};medicalTerm=${rule.medicalTerm}"
            )
            _state.update { it.copy(showRuleEditor = false, selectedRule = null) }
            loadData()
        }
    }

    private fun deleteRule(rule: RuleEntity) {
        viewModelScope.launch {
            db.ruleDao().delete(rule)
            logAudit(
                action = "RULE_DELETE",
                entityType = "RuleEntity",
                entityId = rule.id.toString(),
                details = "symptomKey=${rule.symptomKey};medicalTerm=${rule.medicalTerm}"
            )
            loadData()
        }
    }

    private suspend fun logAudit(
        action: String,
        entityType: String,
        entityId: String?,
        details: String?
    ) {
        db.auditEventDao().insert(
            AuditEventEntity(
                adminId = _state.value.currentAdminId.takeIf { it > 0 },
                action = action,
                entityType = entityType,
                entityId = entityId,
                details = details
            )
        )
    }

    private fun setError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }
}