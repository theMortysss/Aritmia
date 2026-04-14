package my.diplom.aritmia.ui.screen.result

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import my.diplom.aritmia.ui.composable.TopBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.navigation.NavController
import my.diplom.aritmia.ui.screen.SharedViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.ui.screen.result.model.ResultScreenIntent

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    userId: Int,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    navController: NavController,
    sharedViewModel: SharedViewModel,
    viewModel: ResultViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.setSharedViewModel(sharedViewModel)
        viewModel.onIntent(ResultScreenIntent.LoadData(userId))
    }
    val state by viewModel.state.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.navigateToClarify, state.navigateBack) {
        if (state.navigateToClarify) {
            navController.navigate("clarify")
        }
        if (state.navigateBack) {
            onBack()
            viewModel.onIntent(ResultScreenIntent.ResetNavigation)
        }
    }

    LaunchedEffect(state.logout) {
        if (state.logout) {
            onLogout()
        }
    }

    Scaffold(
        topBar = { TopBar(onLogout = { viewModel.onIntent(ResultScreenIntent.Logout) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (state.isLoading || state.diagnosis == null || state.rules.isEmpty()) {
                Text(text = "Загрузка результатов...")
            } else {
                Text(
                    text = "Ваши симптомы: ${state.diagnosis!!.userInput}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (state.recognizedMedicalTerms.isNotEmpty()) {
                    Text(text = "Распознанные симптомы:")
                    state.recognizedMedicalTerms.forEach { term ->
                        Text(text = "- $term")
                    }
                }

                if (state.unrecognizedSymptoms.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Нераспознанные симптомы:")
                    state.unrecognizedSymptoms.forEach { symptom ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "- $symptom")
                            IconButton(onClick = {
                                viewModel.onIntent(ResultScreenIntent.EditSymptom(symptom))
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Уточнить симптом",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Вероятность аритмии: ${state.diagnosis!!.probability}%",
                    style = MaterialTheme.typography.bodyLarge
                )

                if (state.diagnosis!!.probability >= 60) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Рекомендации:",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "- Обратитесь к врачу.\n" +
                                "- Сделайте ЭКГ.\n" +
                                "- Пройдите Холтер-мониторинг.\n" +
                                "С вами свяжется врач, ожидайте. Если звонок не поступил в течение 3-х рабочих дней, обратитесь в поликлинику для записи на дополнительные обследования.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.onIntent(ResultScreenIntent.NavigateBack) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Назад к вводу симптомов")
            }
        }
    }

    if (state.showDialog && state.selectedSymptom != null) {
        var expanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.onIntent(ResultScreenIntent.DismissDialog) },
            title = { Text("Симптом не распознан") },
            text = {
                Column {
                    Text("Симптом \"${state.selectedSymptom}\" не был распознан. Уточните или переформулируйте его:")
                    Spacer(modifier = Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = {
                            expanded = it
                            if (expanded) {
                                keyboardController?.show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = state.editedSymptom,
                            onValueChange = { newText ->
                                viewModel.onIntent(ResultScreenIntent.UpdateEditedSymptom(newText))
                                expanded = newText.isNotBlank()
                            },
                            label = { Text("Уточнённый симптом") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                if (state.editedSymptom.isNotBlank()) {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        modifier = Modifier.clickable {
                                            expanded = !expanded
                                            if (expanded) {
                                                keyboardController?.show()
                                            }
                                        },
                                        expanded = expanded
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    expanded = false
                                }
                            )
                        )

                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            if (state.suggestions.isEmpty() && state.editedSymptom.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Совпадений не найдено") },
                                    onClick = { },
                                    enabled = false
                                )
                            } else {
                                state.suggestions.forEach { suggestion ->
                                    DropdownMenuItem(
                                        text = { Text(suggestion) },
                                        onClick = {
                                            viewModel.onIntent(ResultScreenIntent.SelectSuggestion(suggestion))
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.onIntent(ResultScreenIntent.SaveEditedSymptom)
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                Button(onClick = {
                    viewModel.onIntent(ResultScreenIntent.DismissDialog)
                }) {
                    Text("Оставить как есть")
                }
            }
        )
    }
}