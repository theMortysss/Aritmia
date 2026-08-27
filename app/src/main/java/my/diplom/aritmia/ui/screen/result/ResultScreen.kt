package my.diplom.aritmia.ui.screen.result

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.diplom.aritmia.diagnosis.DiseaseAssessmentStatus
import my.diplom.aritmia.diagnosis.DiseaseCandidate
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
    onContinue: () -> Unit,
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
        withContext(Dispatchers.Main.immediate) {
            if (state.navigateToClarify) navController.navigate("clarify")
            if (state.navigateBack) {
                onBack()
                viewModel.onIntent(ResultScreenIntent.ResetNavigation)
            }
        }
    }
    LaunchedEffect(state.logout) {
        if (state.logout) {
            withContext(Dispatchers.Main.immediate) { onLogout() }
        }
    }

    Scaffold(topBar = { TopBar(onLogout = { viewModel.onIntent(ResultScreenIntent.Logout) }) }) { padding ->
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
                SymptomsCard(
                    recognizedTerms = state.recognizedMedicalTerms,
                    unrecognizedSymptoms = state.unrecognizedSymptoms,
                    onEditSymptom = { viewModel.onIntent(ResultScreenIntent.EditSymptom(it)) }
                )

                DiseaseAssessmentCard(
                    status = state.diseaseAssessmentStatus,
                    recognizedConceptCount = state.recognizedDiseaseConceptCount,
                    candidates = state.diseaseCandidates
                )

                SafetyCard(state.diseaseAssessmentStatus)

                if (state.diseaseAssessmentStatus == DiseaseAssessmentStatus.INSUFFICIENT_EVIDENCE) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Дополнить жалобы", modifier = Modifier.padding(8.dp))
                    }
                    Text(
                        "Уже введённые жалобы будут сохранены. Добавьте только другие реально присутствующие симптомы и повторите оценку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { viewModel.onIntent(ResultScreenIntent.NavigateBack) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Назад к вводу симптомов", modifier = Modifier.padding(8.dp))
                    }
                } else {
                    Button(
                        onClick = { viewModel.onIntent(ResultScreenIntent.NavigateBack) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Назад к вводу симптомов", modifier = Modifier.padding(8.dp))
                    }
                }
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
                    Text("Уточните симптом «${state.selectedSymptom}»:")
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it; if (it) keyboardController?.show() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = state.editedSymptom,
                            onValueChange = { text ->
                                viewModel.onIntent(ResultScreenIntent.UpdateEditedSymptom(text))
                                expanded = text.isNotBlank()
                            },
                            label = { Text("Уточнённый симптом") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = {
                                if (state.editedSymptom.isNotBlank()) {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        modifier = Modifier.clickable { expanded = !expanded },
                                        expanded = expanded
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                keyboardController?.hide(); expanded = false
                            })
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            if (state.suggestions.isEmpty() && state.editedSymptom.isNotBlank()) {
                                DropdownMenuItem(text = { Text("Совпадений не найдено") }, onClick = {}, enabled = false)
                            } else {
                                state.suggestions.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s) },
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
                Button(onClick = { viewModel.onIntent(ResultScreenIntent.SaveEditedSymptom) }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(ResultScreenIntent.DismissDialog) }) { Text("Оставить") }
            }
        )
    }
}

@Composable
private fun SymptomsCard(
    recognizedTerms: List<String>,
    unrecognizedSymptoms: List<String>,
    onEditSymptom: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Симптомы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (recognizedTerms.isNotEmpty()) {
                Text("Распознаны:", color = MaterialTheme.colorScheme.primary)
                recognizedTerms.forEach { Text("• $it") }
            }
            if (unrecognizedSymptoms.isNotEmpty()) {
                Text("Не распознаны:", color = MaterialTheme.colorScheme.error)
                unrecognizedSymptoms.forEach { symptom ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("• $symptom", modifier = Modifier.weight(1f))
                        IconButton(onClick = { onEditSymptom(symptom) }) {
                            Icon(Icons.Default.Info, "Уточнить", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (recognizedTerms.isEmpty() && unrecognizedSymptoms.isEmpty()) {
                Text("Симптомы не указаны", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun DiseaseAssessmentCard(
    status: DiseaseAssessmentStatus,
    recognizedConceptCount: Int,
    candidates: List<DiseaseCandidate>
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (status) {
                DiseaseAssessmentStatus.OUT_OF_SCOPE -> {
                    Text(
                        "Недостаточно данных для сердечно-сосудистой оценки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Во введённых жалобах не распознаны признаки, на которых обучена сердечно-сосудистая модель. Поэтому приложение не будет искусственно распределять проценты между заболеваниями."
                    )
                    Text(
                        "Если жалоба сохраняется или беспокоит вас, обратитесь к подходящему специалисту для очной оценки.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DiseaseAssessmentStatus.INSUFFICIENT_EVIDENCE -> {
                    Text(
                        "Недостаточно признаков для ранжирования заболеваний",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Распознано сердечно-сосудистых признаков: $recognizedConceptCount. Этого объёма информации недостаточно для устойчивого top-5 ранжирования, поэтому приложение не распределяет уверенность модели между заболеваниями."
                    )
                    Text(
                        "Можно продолжить это же обращение: уже введённые жалобы сохранятся, а вы добавите только другие реально присутствующие симптомы.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DiseaseAssessmentStatus.MODEL_UNAVAILABLE -> {
                    Text(
                        "Модель оценки временно недоступна",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Приложение распознало сердечно-сосудистые признаки, но не смогло безопасно загрузить проверенную pretrained-модель. Ранжирование заболеваний отключено, чтобы не подменять её запасной синтетической моделью."
                    )
                    Text(
                        "Повторите попытку после обновления приложения или обратитесь к врачу для оценки жалоб.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DiseaseAssessmentStatus.RANKED -> {
                    Text(
                        "Возможные сердечно-сосудистые состояния",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Показано относительное распределение уверенности модели только между поддерживаемыми классами. Эти проценты не являются клинической вероятностью диагноза.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    candidates.forEachIndexed { index, candidate ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "${index + 1}. ${candidate.name}",
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Medium
                                )
                                Text("${candidate.modelScorePercent}%", fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(
                                progress = { candidate.modelScorePercent / 100f },
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50))
                            )
                            if (candidate.matchedSignals.isNotEmpty()) {
                                Text(
                                    "Учтённые признаки: ${candidate.matchedSignals.take(4).joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyCard(status: DiseaseAssessmentStatus) {
    val container = if (status == DiseaseAssessmentStatus.RANKED) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = if (status == DiseaseAssessmentStatus.RANKED) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Важно", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content)
            Text(
                "Результат приложения не является диагнозом и не заменяет осмотр врача.",
                color = content
            )
            Text(
                "Если появилась сильная или нарастающая боль в груди, выраженная одышка, потеря сознания или резкое ухудшение состояния — нужна срочная медицинская помощь.",
                color = content
            )
        }
    }
}
