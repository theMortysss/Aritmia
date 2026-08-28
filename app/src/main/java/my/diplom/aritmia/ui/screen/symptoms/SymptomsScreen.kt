package my.diplom.aritmia.ui.screen.symptoms

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.ui.composable.TopBar
import my.diplom.aritmia.ui.screen.symptoms.model.SymptomsScreenIntent

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymptomsScreen(
    onDiagnose: (List<String>) -> Unit,
    onLogout: () -> Unit,
    initialSymptoms: List<String> = emptyList(),
    viewModel: SymptomsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(initialSymptoms) {
        viewModel.resetDiagnosedState(initialSymptoms)
    }

    LaunchedEffect(state.navigateToDiagnose) {
        if (state.navigateToDiagnose) {
            onDiagnose(state.symptoms)
            viewModel.resetDiagnosedState()
        }
    }

    LaunchedEffect(state.logout) {
        if (state.logout) onLogout()
    }

    Scaffold(
        topBar = { TopBar(onLogout = { viewModel.onIntent(SymptomsScreenIntent.Logout) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = {
                        expanded = it
                        if (it) keyboardController?.show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = state.newSymptom,
                        onValueChange = { text ->
                            viewModel.onIntent(SymptomsScreenIntent.UpdateNewSymptom(text))
                            expanded = text.isNotBlank()
                        },
                        label = { Text("Введите симптом") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                            .background(Color.Transparent),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = {
                            if (state.newSymptom.isNotBlank()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = expanded,
                                    modifier = Modifier.clickable { expanded = !expanded }
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            viewModel.onIntent(SymptomsScreenIntent.AddSymptom)
                            expanded = false
                            keyboardController?.hide()
                        })
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (state.suggestions.isEmpty() && state.newSymptom.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("Совпадений не найдено") },
                                onClick = {},
                                enabled = false
                            )
                        } else {
                            state.suggestions.forEach { suggestion ->
                                DropdownMenuItem(
                                    text = { Text(suggestion) },
                                    onClick = {
                                        viewModel.onIntent(SymptomsScreenIntent.SelectSuggestion(suggestion))
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        viewModel.onIntent(SymptomsScreenIntent.AddSymptom)
                        keyboardController?.hide()
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Добавить симптом",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            state.showDeleteDialog?.let { symptom ->
                AlertDialog(
                    onDismissRequest = { viewModel.onIntent(SymptomsScreenIntent.DismissDeleteDialog) },
                    title = { Text("Удалить симптом?") },
                    text = { Text("Вы уверены, что хотите удалить симптом \"$symptom\"?") },
                    confirmButton = {
                        Button(onClick = { viewModel.onIntent(SymptomsScreenIntent.ConfirmDelete) }) {
                            Text("Удалить")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.onIntent(SymptomsScreenIntent.DismissDeleteDialog) }) {
                            Text("Отмена")
                        }
                    }
                )
            }

            if (state.symptoms.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Симптомы пока не добавлены",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    items(state.symptoms) { symptom ->
                        AnimatedVisibility(
                            visible = true,
                            enter = slideInVertically { -it } + fadeIn(),
                            exit = slideOutVertically { -it } + fadeOut()
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .shadow(4.dp, RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        symptom,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                                    )
                                    IconButton(onClick = {
                                        viewModel.onIntent(SymptomsScreenIntent.ShowDeleteDialog(symptom))
                                    }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Удалить симптом",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.onIntent(SymptomsScreenIntent.Diagnose) }
                ) {
                    Text(
                        "Диагностировать",
                        modifier = Modifier.padding(8.dp),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
