package my.diplom.aritmia.ui.screen.admin

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.User
import my.diplom.aritmia.ui.composable.TopBar
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
    val tabs = listOf("Пользователи", "Правила")
    val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    LaunchedEffect(Unit) {
        viewModel.state.collect { state ->
            if (state.logout) {
                onLogout()
            }
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
                    interactionSource = remember { MutableInteractionSource() },
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

            when (state.selectedTabIndex) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Button(onClick = {
                            viewModel.onIntent(AdminScreenIntent.SelectUser(null))
                            viewModel.onIntent(AdminScreenIntent.ShowUserEditor)
                        }) {
                            Text("Добавить пользователя")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        } else if (state.users.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Пользователи отсутствуют",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                )
                            }
                        } else {
                            LazyColumn {
                                items(state.users) { user ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(text = "ФИО: ${user.fullName}")
                                            Text(text = "Телефон: ${user.phone}")
                                            Text(
                                                text = "Роль: ${
                                                    when (user.role) {
                                                        Role.PATIENT -> "Пациент"
                                                        Role.DOCTOR -> "Врач"
                                                        Role.ADMIN -> "Админ"
                                                    }
                                                }"
                                            )
                                            Text(text = "Пароль: ${user.password}")
                                            user.gender?.let { Text(text = "Пол: $it") }
                                            user.age?.let { Text(text = "Возраст: $it") }
                                            user.specialty?.let { Text(text = "Специальность: $it") }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Button(onClick = {
                                                    viewModel.onIntent(AdminScreenIntent.SelectUser(user))
                                                    viewModel.onIntent(AdminScreenIntent.ShowUserEditor)
                                                }) {
                                                    Text("Редактировать")
                                                }
                                                Button(onClick = {
                                                    viewModel.onIntent(AdminScreenIntent.DeleteUser(user))
                                                }) {
                                                    Text("Удалить")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.showUserEditor) {
                        UserEditorDialog(
                            user = state.selectedUser,
                            onSave = { user ->
                                viewModel.onIntent(AdminScreenIntent.SaveUser(user))
                                viewModel.onIntent(AdminScreenIntent.HideUserEditor)
                            },
                            onDismiss = { viewModel.onIntent(AdminScreenIntent.HideUserEditor) },
                            viewModel = viewModel
                        )
                    }
                }
                1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Button(onClick = {
                            viewModel.onIntent(AdminScreenIntent.SelectRule(null))
                            viewModel.onIntent(AdminScreenIntent.ShowRuleEditor)
                        }) {
                            Text("Добавить правило")
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn {
                            items(state.rules) { rule ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(text = "Ключ: ${rule.symptomKey}")
                                        Text(text = "Термин: ${rule.medicalTerm}")
                                        Text(text = "Вес: ${rule.probabilityWeight}")
                                        Text(text = "Вопросы: ${rule.clarifyingQuestions ?: "Нет"}")
                                        Text(text = "Триггеры ответов: ${rule.answerTriggers ?: "Нет"}")
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Button(onClick = {
                                                viewModel.onIntent(AdminScreenIntent.SelectRule(rule))
                                                viewModel.onIntent(AdminScreenIntent.ShowRuleEditor)
                                            }) {
                                                Text("Редактировать")
                                            }
                                            Button(onClick = { viewModel.onIntent(AdminScreenIntent.DeleteRule(rule)) }) {
                                                Text("Удалить")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.showRuleEditor) {
                        RuleEditorDialog(
                            rule = state.selectedRule,
                            onSave = { rule ->
                                viewModel.onIntent(AdminScreenIntent.SaveRule(rule))
                                viewModel.onIntent(AdminScreenIntent.HideRuleEditor)
                            },
                            onDismiss = { viewModel.onIntent(AdminScreenIntent.HideRuleEditor) }
                        )
                    }
                }
            }
        }
    }
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
                    .verticalScroll(scrollState)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { focusManager.clearFocus() }
                    )
            ) {
                OutlinedTextField(
                    value = state.tempFullName,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdateFullName(it)) },
                    label = { Text("ФИО") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    isError = state.tempFullName.isNotBlank() && !state.tempFullName.matches(Regex("^[А-ЯA-Z][а-яa-z]+([\\s-][А-ЯA-Z][а-яa-z]+)*\$"))
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.tempPhone,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdatePhone(it)) },
                    label = { Text("Номер телефона (+7-xxx-xxx-xx-xx)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    visualTransformation = phoneMask,
                    isError = state.tempPhone.isNotBlank() && state.tempPhone.length != 10
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.tempPassword,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdatePassword(it)) },
                    label = { Text("Пароль") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    isError = state.tempPassword.isNotBlank() && state.tempPassword.length < 6
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("Роль:")
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(Role.entries) { roleOption ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    viewModel.onIntent(AdminScreenIntent.UpdateRole(roleOption))
                                }
                                .padding(end = 16.dp)
                        ) {
                            RadioButton(
                                selected = state.tempRole == roleOption,
                                onClick = { viewModel.onIntent(AdminScreenIntent.UpdateRole(roleOption)) }
                            )
                            Text(
                                when (roleOption) {
                                    Role.PATIENT -> "Пациент"
                                    Role.DOCTOR -> "Врач"
                                    Role.ADMIN -> "Админ"
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.tempGender,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdateGender(it)) },
                    label = { Text("Пол (необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.tempAge,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdateAge(it)) },
                    label = { Text("Возраст (необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    isError = state.tempAge.isNotBlank() && (state.tempAge.toIntOrNull() ?: 0) !in 1..150
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.tempSpecialty,
                    onValueChange = { viewModel.onIntent(AdminScreenIntent.UpdateSpecialty(it)) },
                    label = { Text("Специальность (необязательно, для врачей)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                state.errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
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
                            gender = state.tempGender,
                            age = state.tempAge.toIntOrNull(),
                            specialty = state.tempSpecialty
                        )
                    )
                },
                enabled = state.tempFullName.isNotBlank() && state.tempPhone.isNotBlank() && state.tempPassword.isNotBlank()
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}