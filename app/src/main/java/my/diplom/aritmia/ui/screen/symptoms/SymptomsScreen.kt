package my.diplom.aritmia.ui.screen.symptoms

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.ui.composable.TopBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.SymptomEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Alignment
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.ui.screen.symptoms.model.SymptomsScreenIntent

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymptomsScreen(
    onDiagnose: (List<String>) -> Unit,
    onLogout: () -> Unit,
    viewModel: SymptomsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        viewModel.resetDiagnosedState()
    }

    LaunchedEffect(state.navigateToDiagnose) {
        if (state.navigateToDiagnose) {
            onDiagnose(state.symptoms)
            viewModel.resetDiagnosedState()
        }
    }

    LaunchedEffect(state.logout) {
        if (state.logout) {
            onLogout()
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                onLogout = { viewModel.onIntent(SymptomsScreenIntent.Logout) }
            )
        }
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
                    .padding(
                        top = 8.dp,
                        start = 8.dp,
                        end = 8.dp,
                        bottom = 16.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = {
                        expanded = it
                        if (expanded) {
                            keyboardController?.show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = state.newSymptom,
                        onValueChange = { newText ->
                            viewModel.onIntent(SymptomsScreenIntent.UpdateNewSymptom(newText))
                            expanded = newText.isNotBlank()
                        },
                        label = { Text("Введите симптом") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                            .background(Color.Transparent),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = {
                            if (state.newSymptom.isNotBlank()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    modifier = Modifier.clickable {
                                        expanded = !expanded
                                        if (expanded) {
                                            keyboardController?.show()
                                        }
                                    },
                                    expanded = expanded,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                viewModel.onIntent(SymptomsScreenIntent.AddSymptom)
                                expanded = false
                                keyboardController?.hide()
                            }
                        )
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                        }
                    ) {
                        if (state.suggestions.isEmpty() && state.newSymptom.isNotBlank()) {
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
                                        viewModel.onIntent(SymptomsScreenIntent.SelectSuggestion(suggestion))
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
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
                        imageVector = Icons.Default.Add,
                        contentDescription = "Добавить симптом",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.showDeleteDialog != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.onIntent(SymptomsScreenIntent.DismissDeleteDialog) },
                    title = { Text("Удалить симптом?") },
                    text = { Text("Вы уверены, что хотите удалить симптом \"${state.showDeleteDialog}\"?") },
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

            if (state.symptoms.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(state.symptoms) { symptom ->
                        AnimatedVisibility(
                            visible = state.symptoms.contains(symptom),
                            enter = slideInVertically(
                                initialOffsetY = { -it },
                                animationSpec = tween(300)
                            ) + fadeIn(animationSpec = tween(300)),
                            exit = slideOutVertically(
                                targetOffsetY = { -it },
                                animationSpec = tween(300)
                            ) + fadeOut(animationSpec = tween(300))
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .shadow(4.dp, RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = symptom,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        viewModel.onIntent(SymptomsScreenIntent.ShowDeleteDialog(symptom))
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
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
                        modifier = Modifier.padding(8.dp),
                        text = "Диагностировать",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Симптомы пока не добавлены",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }
    }
}
