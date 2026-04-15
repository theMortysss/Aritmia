package my.diplom.aritmia.ui.screen.result

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import my.diplom.aritmia.ui.composable.TopBar
import my.diplom.aritmia.ui.screen.SharedViewModel
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

    val state           by viewModel.state.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.navigateToClarify, state.navigateBack) {
        if (state.navigateToClarify) navController.navigate("clarify")
        if (state.navigateBack) {
            onBack()
            viewModel.onIntent(ResultScreenIntent.ResetNavigation)
        }
    }
    LaunchedEffect(state.logout) { if (state.logout) onLogout() }

    Scaffold(
        topBar = { TopBar(onLogout = { viewModel.onIntent(ResultScreenIntent.Logout) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.isLoading || state.diagnosis == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // ── Карточка симптомов ─────────────────────────────────────────
                SymptomsCard(
                    recognizedTerms    = state.recognizedMedicalTerms,
                    unrecognizedSymptoms = state.unrecognizedSymptoms,
                    onEditSymptom      = { viewModel.onIntent(ResultScreenIntent.EditSymptom(it)) }
                )

                // ── Карточка вероятности ──────────────────────────────────────
                NnProbabilityCard(nnProbability = state.nnProbability)

                // ── Рекомендации при высокой вероятности ──────────────────────
                val prob = state.nnProbability ?: state.diagnosis!!.probability
                if (prob >= 60) RecommendationsCard()

                Button(
                    onClick  = { viewModel.onIntent(ResultScreenIntent.NavigateBack) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Назад к вводу симптомов", modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

    // ── Диалог уточнения нераспознанного симптома ──────────────────────────────
    if (state.showDialog && state.selectedSymptom != null) {
        var expanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.onIntent(ResultScreenIntent.DismissDialog) },
            title = { Text("Симптом не распознан") },
            text = {
                Column {
                    Text("Уточните симптом «${state.selectedSymptom}»:")
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded         = expanded,
                        onExpandedChange = { expanded = it; if (it) keyboardController?.show() },
                        modifier         = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value       = state.editedSymptom,
                            onValueChange = { text ->
                                viewModel.onIntent(ResultScreenIntent.UpdateEditedSymptom(text))
                                expanded = text.isNotBlank()
                            },
                            label    = { Text("Уточнённый симптом") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = {
                                if (state.editedSymptom.isNotBlank())
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        modifier = Modifier.clickable { expanded = !expanded },
                                        expanded = expanded
                                    )
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                keyboardController?.hide(); expanded = false
                            })
                        )
                        ExposedDropdownMenu(
                            expanded         = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            if (state.suggestions.isEmpty() && state.editedSymptom.isNotBlank()) {
                                DropdownMenuItem(
                                    text    = { Text("Совпадений не найдено") },
                                    onClick = {}, enabled = false
                                )
                            } else {
                                state.suggestions.forEach { s ->
                                    DropdownMenuItem(
                                        text    = { Text(s) },
                                        onClick = {
                                            viewModel.onIntent(ResultScreenIntent.SelectSuggestion(s))
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
                Button(onClick = { viewModel.onIntent(ResultScreenIntent.SaveEditedSymptom) }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                Button(onClick = { viewModel.onIntent(ResultScreenIntent.DismissDialog) }) {
                    Text("Оставить")
                }
            }
        )
    }
}

// ── Карточки ───────────────────────────────────────────────────────────────────

@Composable
private fun SymptomsCard(
    recognizedTerms: List<String>,
    unrecognizedSymptoms: List<String>,
    onEditSymptom: (String) -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Симптомы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (recognizedTerms.isNotEmpty()) {
                Text("Распознаны:", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                recognizedTerms.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (unrecognizedSymptoms.isNotEmpty()) {
                if (recognizedTerms.isNotEmpty()) Spacer(Modifier.height(4.dp))
                Text("Не распознаны:", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
                unrecognizedSymptoms.forEach { symptom ->
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text("• $symptom", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { onEditSymptom(symptom) }) {
                            Icon(Icons.Default.Info, "Уточнить",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (recognizedTerms.isEmpty() && unrecognizedSymptoms.isEmpty()) {
                Text("Симптомы не указаны",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun NnProbabilityCard(nnProbability: Int?) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Вероятность аритмии",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (nnProbability != null) {
                val color = probabilityColor(nnProbability)
                val animatedProgress by animateFloatAsState(
                    targetValue   = nnProbability / 100f,
                    animationSpec = tween(900),
                    label         = "nn_progress"
                )

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "Нейронная сеть (MLP)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "$nnProbability%",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize   = 28.sp
                        ),
                        color = color
                    )
                }

                LinearProgressIndicator(
                    progress   = { animatedProgress },
                    modifier   = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(50)),
                    color      = color,
                    trackColor = color.copy(alpha = 0.15f),
                    strokeCap  = StrokeCap.Round
                )

                // Текстовая интерпретация
                val interpretation = when {
                    nnProbability >= 75 -> "Высокая вероятность аритмии"
                    nnProbability >= 50 -> "Умеренная вероятность аритмии"
                    nnProbability >= 25 -> "Низкая вероятность аритмии"
                    else                -> "Признаки аритмии не выявлены"
                }
                Text(
                    interpretation,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Нейросеть анализирует данные...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun RecommendationsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Рекомендации",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onErrorContainer
            )
            listOf(
                "Обратитесь к кардиологу.",
                "Сделайте ЭКГ.",
                "Пройдите Холтер-мониторинг.",
                "С вами свяжется врач. Если звонок не поступил в течение 3 рабочих дней — запишитесь самостоятельно."
            ).forEach {
                Text("• $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun probabilityColor(probability: Int): Color = when {
    probability >= 60 -> MaterialTheme.colorScheme.error
    probability >= 30 -> Color(0xFFF57C00)
    else              -> Color(0xFF388E3C)
}
