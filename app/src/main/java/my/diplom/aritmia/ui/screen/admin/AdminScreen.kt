package my.diplom.aritmia.ui.screen.admin

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.User
import my.diplom.aritmia.ui.composable.TopBar
import my.diplom.aritmia.ui.screen.admin.model.AdminDashboardMetrics
import my.diplom.aritmia.ui.screen.admin.model.AdminScreenIntent
import my.diplom.aritmia.ui.screen.doctor.RuleEditorDialog
import my.diplom.aritmia.utils.MaskVisualTransformation
import java.time.format.DateTimeFormatter

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminScreenViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val focusManager: FocusManager = LocalFocusManager.current
    val tabs = listOf("Обзор", "Пользователи", "Правила", "Аудит")

    LaunchedEffect(Unit) {
        viewModel.state.collect { current ->
            if (current.logout) onLogout()
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                title = "Админ панель",
                onLogout = { viewModel.onIntent(AdminScreenIntent.Logout) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { focusManager.clearFocus() }
                )
        ) {
            TabRow(selectedTabIndex = state.selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        text = { Text(title) },
                        selected = state.selectedTabIndex == index,
                        onClick = { viewModel.onIntent(AdminScreenIntent.ChangeTab(index)) }
                    )
                }
            }

            if (state.isLoading && state.users.isEmpty() && state.selectedTabIndex == 0) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(32.dp)
                )
            } else {
                when (state.selectedTabIndex) {
                    0 -> AdminOverview(
                        metrics = state.metrics,
                        modelAvailable = state.modelAvailable,
                        modelVersion = state.modelVersion,
                        extractorVersion = state.extractorVersion
                    )

                    1 -> UsersTab(
                        users = state.users,
                        search = state.userSearch,
                        currentAdminId = state.currentAdminId,
                        errorMessage = state.errorMessage,
                        onSearch = { viewModel.onIntent(AdminScreenIntent.UpdateUserSearch(it)) },
                        onAdd = {
                            viewModel.onIntent(AdminScreenIntent.SelectUser(null))
                            viewModel.onIntent(AdminScreenIntent.ShowUserEditor)
                        },
                        onEdit = {
                            viewModel.onIntent(AdminScreenIntent.SelectUser(it))
                            viewModel.onIntent(AdminScreenIntent.ShowUserEditor)
                        },
                        onSetActive = { user, active ->
                            viewModel.onIntent(AdminScreenIntent.SetUserActive(user, active))
                        },
                        onDelete = { viewModel.onIntent(AdminScreenIntent.DeleteUser(it)) }
                    )

                    2 -> RulesTab(
                        rules = state.rules,
                        onAdd = {
                            viewModel.onIntent(AdminScreenIntent.SelectRule(null))
                            viewModel.onIntent(AdminScreenIntent.ShowRuleEditor)
                        },
                        onEdit = {
                            viewModel.onIntent(AdminScreenIntent.SelectRule(it))
                            viewModel.onIntent(AdminScreenIntent.ShowRuleEditor)
                        },
                        onDelete = { viewModel.onIntent(AdminScreenIntent.DeleteRule(it)) }
                    )

                    3 -> AuditTab(state.auditEvents)
                }
            }
        }
    }

    if (state.showUserEditor) {
        UserEditorDialog(
            user = state.selectedUser,
            onSave = { viewModel.onIntent(AdminScreenIntent.SaveUser(it)) },
            onDismiss = { viewModel.onIntent(AdminScreenIntent.HideUserEditor) },
            viewModel = viewModel
        )
    }

    if (state.showRuleEditor) {
        RuleEditorDialog(
            rule = state.selectedRule,
            onSave = { viewModel.onIntent(AdminScreenIntent.SaveRule(it)) },
            onDismiss = { viewModel.onIntent(AdminScreenIntent.HideRuleEditor) }
        )
    }
}

@Composable
private fun AdminOverview(
    metrics: AdminDashboardMetrics,
    modelAvailable: Boolean,
    modelVersion: String,
    extractorVersion: String
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AdminSectionCard("Диагностическая система") {
                MetricRow("Pretrained model", if (modelAvailable) "доступна" else "недоступна")
                MetricRow("Версия модели", modelVersion.ifBlank { "—" })
                MetricRow("Версия extractor", extractorVersion.ifBlank { "—" })
                Text(
                    "Редактируемые правила управляют подсказками, уточнениями и medical terms, но не изменяют pretrained disease ranking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            AdminSectionCard("Пользователи") {
                MetricRow("Всего", metrics.users.toString())
                MetricRow("Активных", metrics.activeUsers.toString())
                MetricRow("Пациентов", metrics.patients.toString())
                MetricRow("Врачей", metrics.doctors.toString())
                MetricRow("Администраторов", metrics.admins.toString())
            }
        }
        item {
            AdminSectionCard("Обращения") {
                MetricRow("Всего", metrics.assessments.toString())
                MetricRow("RANKED", metrics.ranked.toString())
                MetricRow("INSUFFICIENT_EVIDENCE", metrics.insufficientEvidence.toString())
                MetricRow("OUT_OF_SCOPE", metrics.outOfScope.toString())
                MetricRow("MODEL_UNAVAILABLE", metrics.modelUnavailable.toString())
                MetricRow("Требуют внимания врача", metrics.needsDoctorAttention.toString())
            }
        }
        item {
            AdminSectionCard("Работа врача") {
                MetricRow("Новых", metrics.newForDoctor.toString())
                MetricRow("Связались", metrics.contacted.toString())
                MetricRow("Закрыто", metrics.closed.toString())
            }
        }
    }
}

@Composable
private fun AdminSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UsersTab(
    users: List<User>,
    search: String,
    currentAdminId: Int,
    errorMessage: String?,
    onSearch: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (User) -> Unit,
    onSetActive: (User, Boolean) -> Unit,
    onDelete: (User) -> Unit
) {
    val query = search.trim()
    val filtered = users.filter { user ->
        query.isBlank() || user.fullName.contains(query, ignoreCase = true) ||
            user.phone.contains(query, ignoreCase = true) ||
            roleLabel(user.role).contains(query, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            label = { Text("Поиск по ФИО, телефону или роли") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("Добавить пользователя")
        }
        errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { user ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(user.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(user.phone)
                        Text("Роль: ${roleLabel(user.role)}")
                        Text(
                            if (user.isActive) "Статус: активен" else "Статус: заблокирован",
                            color = if (user.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        user.age?.let { Text("Возраст: $it") }
                        user.gender?.let { Text("Пол: $it") }
                        user.specialty?.let { Text("Специальность: $it") }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { onEdit(user) }, modifier = Modifier.weight(1f)) {
                                Text("Редактировать")
                            }
                            Button(
                                onClick = { onSetActive(user, !user.isActive) },
                                modifier = Modifier.weight(1f),
                                enabled = user.id != currentAdminId
                            ) {
                                Text(if (user.isActive) "Заблокировать" else "Разблокировать")
                            }
                        }
                        TextButton(
                            onClick = { onDelete(user) },
                            enabled = user.id != currentAdminId,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Удалить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesTab(
    rules: List<my.diplom.aritmia.data.RuleEntity>,
    onAdd: () -> Unit,
    onEdit: (my.diplom.aritmia.data.RuleEntity) -> Unit,
    onDelete: (my.diplom.aritmia.data.RuleEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Правила влияют на подсказки, уточняющие вопросы и отображаемые medical terms. Вес legacy-правила больше не показывается и не редактируется.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Добавить правило") }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rules, key = { it.id }) { rule ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Ключ: ${rule.symptomKey}", fontWeight = FontWeight.Bold)
                        Text("Термин: ${rule.medicalTerm}")
                        Text("Вопросы: ${rule.clarifyingQuestions ?: "Нет"}")
                        Text("Триггеры: ${rule.answerTriggers ?: "Нет"}")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { onEdit(rule) }, modifier = Modifier.weight(1f)) {
                                Text("Редактировать")
                            }
                            TextButton(onClick = { onDelete(rule) }, modifier = Modifier.weight(1f)) {
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun AuditTab(events: List<my.diplom.aritmia.data.AuditEventEntity>) {
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (events.isEmpty()) {
            item { Text("Административных изменений пока нет") }
        }
        items(events, key = { it.id }) { event ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(event.action, fontWeight = FontWeight.Bold)
                    Text("${event.entityType} #${event.entityId ?: "—"}")
                    event.details?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Администратор: ${event.adminId ?: "—"} · ${event.createdAt.format(formatter)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun roleLabel(role: Role): String = when (role) {
    Role.PATIENT -> "Пациент"
    Role.DOCTOR -> "Врач"
    Role.ADMIN -> "Администратор"
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun UserEditorDialog(
    user: User?,
    onSave: (User) -> Unit,
    onDismiss: () -> Unit,
    viewModel: AdminScreenViewModel
) {
    val state by viewModel.state.collectAsState()
    val focusManager: FocusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val phoneMask = MaskVisualTransformation("+7-###-###-##-##")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (user == null) "Создать пользователя" else "Редактировать пользователя") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.tempFullName,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdateFullName(it)) },
                    label = { Text("ФИО") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                OutlinedTextField(
                    value = state.tempPhone,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdatePhone(it)) },
                    label = { Text("Номер телефона (+7-xxx-xxx-xx-xx)") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = phoneMask,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                OutlinedTextField(
                    value = state.tempPassword,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdatePassword(it)) },
                    label = {
                        Text(if (user == null) "Пароль" else "Новый пароль (необязательно)")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )

                Text("Роль:")
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(Role.entries) { roleOption ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                viewModel.onIntent(AdminScreenIntent.UpdateRole(roleOption))
                            }
                        ) {
                            RadioButton(
                                selected = state.tempRole == roleOption,
                                onClick = { viewModel.onIntent(AdminScreenIntent.UpdateRole(roleOption)) }
                            )
                            Text(roleLabel(roleOption))
                        }
                    }
                }

                if (state.tempRole == Role.PATIENT) {
                    OutlinedTextField(
                        value = state.tempGender,
                        onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdateGender(it)) },
                        label = { Text("Пол (необязательно)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.tempAge,
                        onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdateAge(it)) },
                        label = { Text("Возраст (необязательно)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                if (state.tempRole == Role.DOCTOR) {
                    OutlinedTextField(
                        value = state.tempSpecialty,
                        onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdateSpecialty(it)) },
                        label = { Text("Специальность") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSave(
                        User(
                            id = user?.id ?: 0,
                            fullName = state.tempFullName,
                            phone = state.tempPhone,
                            password = state.tempPassword,
                            role = state.tempRole,
                            gender = state.tempGender.ifBlank { null },
                            age = state.tempAge.toIntOrNull(),
                            specialty = state.tempSpecialty.ifBlank { null },
                            isActive = user?.isActive ?: true
                        )
                    )
                },
                enabled = state.tempFullName.isNotBlank() &&
                    state.tempPhone.length == 10 &&
                    (user != null || state.tempPassword.length >= 6)
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
