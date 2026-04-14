package my.diplom.aritmia.ui.screen.login

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import my.diplom.aritmia.data.AppDatabase
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import my.diplom.aritmia.ui.composable.TopBar
import my.diplom.aritmia.utils.MaskVisualTransformation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.User
import my.diplom.aritmia.ui.screen.login.model.LoginScreenIntent

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LoginScreen(
    onLoginSuccess: (User) -> Unit,
    navController: NavController,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val focusManager: FocusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val tabs = listOf("Вход", "Регистрация")
    val phoneMask = MaskVisualTransformation("+7-###-###-##-##")

    LaunchedEffect(state.loginSuccess) {
        state.loginSuccess?.let { user ->
            onLoginSuccess(user)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { focusManager.clearFocus() }
                )
        ) {
            Text(
                text = "Вход в приложение",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            TabRow(selectedTabIndex = state.selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        text = { Text(title) },
                        selected = state.selectedTabIndex == index,
                        onClick = { viewModel.onIntent(LoginScreenIntent.ChangeTab(index)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (state.selectedTabIndex) {
                0 -> {
                    OutlinedTextField(
                        value = state.phone,
                        onValueChange = { viewModel.onIntent(LoginScreenIntent.UpdatePhone(it)) },
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
                        isError = state.phone.isNotBlank() && state.phone.length != 10
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { viewModel.onIntent(LoginScreenIntent.UpdatePassword(it)) },
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
                        isError = state.password.isNotBlank() && state.password.length < 6
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Роль:")
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Role.entries.filter { it != Role.ADMIN }.forEach { roleOption ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        viewModel.onIntent(
                                            LoginScreenIntent.UpdateRole(
                                                roleOption
                                            )
                                        )
                                    }
                                    .padding(8.dp)
                            ) {
                                RadioButton(
                                    selected = state.role == roleOption,
                                    onClick = { viewModel.onIntent(LoginScreenIntent.UpdateRole(roleOption)) }
                                )
                                Text(
                                    when(roleOption)  {
                                        Role.PATIENT -> "Пациент"
                                        Role.DOCTOR -> "Врач"
                                        Role.ADMIN -> ""
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    state.errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.onIntent(LoginScreenIntent.Login)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Войти")
                    }
                }
                1 -> {
                    OutlinedTextField(
                        value = state.fullName,
                        onValueChange = { viewModel.onIntent(LoginScreenIntent.UpdateFullName(it)) },
                        label = { Text("ФИО") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        isError = state.fullName.isNotBlank() && !state.fullName.matches(Regex("^[А-ЯA-Z][а-яa-z]+([\\s-][А-ЯA-Z][а-яa-z]+)*\$"))
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.phone,
                        onValueChange = { viewModel.onIntent(LoginScreenIntent.UpdatePhone(it)) },
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
                        isError = state.phone.isNotBlank() && state.phone.length != 10
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.gender,
                        onValueChange = { viewModel.onIntent(LoginScreenIntent.UpdateGender(it)) },
                        label = { Text("Пол") },
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
                        value = state.age,
                        onValueChange = { viewModel.onIntent(LoginScreenIntent.UpdateAge(it)) },
                        label = { Text("Возраст") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        isError = state.age.isNotBlank() && (state.age.toIntOrNull() ?: 0) !in 1..150
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { viewModel.onIntent(LoginScreenIntent.UpdatePassword(it)) },
                        label = { Text("Пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        isError = state.password.isNotBlank() && state.password.length < 6
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Регистрация доступна только для пациентов.\nДля регистрации врача обратитесь к администратору.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    state.errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.onIntent(LoginScreenIntent.Register)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Зарегистрироваться")
                    }
                }
            }
        }
    }
}

