package my.diplom.aritmia.ui.screen.doctor

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.data.AssessmentWorkflow
import my.diplom.aritmia.ui.composable.TopBar
import my.diplom.aritmia.ui.screen.doctor.model.DoctorAssessmentItem
import my.diplom.aritmia.ui.screen.doctor.model.DoctorScreenIntent
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorScreen(
    viewModel: DoctorScreenViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current
    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm") }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.logout) {
        if (state.logout) onLogout()
    }

    Scaffold(
        topBar = {
            TopBar(
                title = "Меню врача",
                onLogout = { viewModel.onIntent(DoctorScreenIntent.Logout) }
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
            AssessmentQueue(
                state = state,
                dateFormatter = dateFormatter,
                onIntent = viewModel::onIntent
            )
        }

        if (state.showFilterSheet) {
            DoctorFilterSheet(
                phone = state.tempPhoneFilter,
                name = state.tempNameFilter,
                startDate = state.tempStartDate,
                endDate = state.tempEndDate,
                dateFormatter = dateFormatter,
                showStartDatePicker = showStartDatePicker,
                showEndDatePicker = showEndDatePicker,
                onShowStartDatePicker = { showStartDatePicker = it },
                onShowEndDatePicker = { showEndDatePicker = it },
                onIntent = viewModel::onIntent
            )
        }
    }

    state.selectedAssessment?.takeIf { state.showAssessmentDialog }?.let { selected ->
        AssessmentDetailsDialog(
            item = selected,
            timeline = state.patientTimeline,
            doctorNote = state.doctorNoteDraft,
            dateFormatter = dateFormatter,
            onNoteChange = { viewModel.onIntent(DoctorScreenIntent.UpdateDoctorNote(it)) },
            onSaveWorkflow = { viewModel.onIntent(DoctorScreenIntent.SaveAssessmentWorkflow(it)) },
            onDismiss = { viewModel.onIntent(DoctorScreenIntent.CloseAssessment) }
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun AssessmentQueue(
    state: my.diplom.aritmia.ui.screen.doctor.model.DoctorScreenState,
    dateFormatter: DateTimeFormatter,
    onIntent: (DoctorScreenIntent) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { onIntent(DoctorScreenIntent.ShowFilterSheet) }) {
                Text("Фильтры")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.attentionOnly,
                    onCheckedChange = { onIntent(DoctorScreenIntent.SetAttentionOnly(it)) }
                )
                Text("Требуют внимания", style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val statuses = listOf(
                "ALL",
                "INSUFFICIENT_EVIDENCE",
                "RANKED",
                "OUT_OF_SCOPE",
                "MODEL_UNAVAILABLE"
            )
            items(statuses) { status ->
                FilterChip(
                    selected = state.statusFilter == status,
                    onClick = { onIntent(DoctorScreenIntent.SetStatusFilter(status)) },
                    label = { Text(assessmentStatusLabel(status)) }
                )
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val workflows = listOf("ALL") + AssessmentWorkflow.values.toList()
            items(workflows) { workflow ->
                FilterChip(
                    selected = state.workflowFilter == workflow,
                    onClick = { onIntent(DoctorScreenIntent.SetWorkflowFilter(workflow)) },
                    label = { Text(workflowLabel(workflow)) }
                )
            }
        }

        Text(
            "Обращений: ${state.totalCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.assessments.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Обращения по заданным фильтрам не найдены",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.assessments, key = { it.assessment.id }) { item ->
                    AssessmentQueueCard(item, dateFormatter) {
                        onIntent(DoctorScreenIntent.OpenAssessment(item.assessment.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssessmentQueueCard(
    item: DoctorAssessmentItem,
    dateFormatter: DateTimeFormatter,
    onOpen: () -> Unit
) {
    val assessment = item.assessment
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (assessment.needsDoctorAttention) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    item.user?.fullName ?: "Пациент #${assessment.patientId}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(assessment.createdAt.format(dateFormatter), style = MaterialTheme.typography.bodySmall)
            }
            item.user?.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Оценка: ${assessmentStatusLabel(assessment.status)}")
            Text("Работа врача: ${workflowLabel(assessment.workflowStatus)}")
            Text(
                assessment.complaints,
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Распознано признаков: ${item.conceptLabels.size}", style = MaterialTheme.typography.bodySmall)
            item.candidates.firstOrNull()?.let {
                Text("Top-1 модели: ${it.name} — ${it.modelScorePercent}%", style = MaterialTheme.typography.bodySmall)
            }
            if (assessment.needsDoctorAttention) {
                Text(
                    "Требует внимания",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("doctor_open_assessment_${assessment.id}")
            ) {
                Text("Открыть обращение")
            }
        }
    }
}

@Composable
private fun AssessmentDetailsDialog(
    item: DoctorAssessmentItem,
    timeline: List<DoctorAssessmentItem>,
    doctorNote: String,
    dateFormatter: DateTimeFormatter,
    onNoteChange: (String) -> Unit,
    onSaveWorkflow: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val assessment = item.assessment
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Обращение пациента") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(item.user?.fullName ?: "Пациент #${assessment.patientId}", fontWeight = FontWeight.Bold)
                item.user?.let { user ->
                    Text("Телефон: ${user.phone}")
                    user.age?.let { Text("Возраст: $it") }
                }
                Text("Дата: ${assessment.createdAt.format(dateFormatter)}")
                Text("Статус оценки: ${assessmentStatusLabel(assessment.status)}", fontWeight = FontWeight.Bold)

                HorizontalDivider()
                Text("Исходные жалобы", fontWeight = FontWeight.Bold)
                assessment.complaints.split(". ").filter { it.isNotBlank() }.forEach { Text("• $it") }

                Text("Распознанные признаки", fontWeight = FontWeight.Bold)
                if (item.conceptLabels.isEmpty()) Text("Нет")
                else item.conceptLabels.forEach { Text("• $it") }

                if (item.candidates.isNotEmpty()) {
                    Text("Результат модели", fontWeight = FontWeight.Bold)
                    Text(
                        "Проценты — относительная уверенность модели между поддерживаемыми классами, а не клиническая вероятность диагноза.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    item.candidates.forEachIndexed { index, candidate ->
                        Text("${index + 1}. ${candidate.name} — ${candidate.modelScorePercent}%")
                        if (candidate.matchedSignals.isNotEmpty()) {
                            Text(
                                "Признаки: ${candidate.matchedSignals.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text(
                    "Версии: model=${assessment.modelVersion}, extractor=${assessment.extractorVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
                Text("Работа с обращением", fontWeight = FontWeight.Bold)
                Text("Текущий статус: ${workflowLabel(assessment.workflowStatus)}")
                OutlinedTextField(
                    value = doctorNote,
                    onValueChange = onNoteChange,
                    label = { Text("Комментарий врача") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssessmentWorkflow.values.forEach { workflow ->
                        Button(
                            onClick = { onSaveWorkflow(workflow) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("doctor_workflow_$workflow")
                        ) {
                            Text(workflowLabel(workflow))
                        }
                    }
                }

                HorizontalDivider()
                Text("История пациента", fontWeight = FontWeight.Bold)
                timeline.forEach { history ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(history.assessment.createdAt.format(dateFormatter), fontWeight = FontWeight.Medium)
                            Text(assessmentStatusLabel(history.assessment.status))
                            Text("Врач: ${workflowLabel(history.assessment.workflowStatus)}")
                            Text(history.assessment.complaints, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            history.candidates.firstOrNull()?.let {
                                Text("Top-1: ${it.name} — ${it.modelScorePercent}%", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoctorFilterSheet(
    phone: String,
    name: String,
    startDate: java.time.LocalDateTime?,
    endDate: java.time.LocalDateTime?,
    dateFormatter: DateTimeFormatter,
    showStartDatePicker: Boolean,
    showEndDatePicker: Boolean,
    onShowStartDatePicker: (Boolean) -> Unit,
    onShowEndDatePicker: (Boolean) -> Unit,
    onIntent: (DoctorScreenIntent) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(DoctorScreenIntent.HideFilterSheet) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Фильтры обращений", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = phone,
                onValueChange = { onIntent(DoctorScreenIntent.UpdateTempFilter(phone = it)) },
                label = { Text("Телефон") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = name,
                onValueChange = { onIntent(DoctorScreenIntent.UpdateTempFilter(name = it)) },
                label = { Text("ФИО") },
                modifier = Modifier.fillMaxWidth()
            )
            DateFilterRow("С", startDate, dateFormatter, { onShowStartDatePicker(true) }) {
                onIntent(DoctorScreenIntent.UpdateTempFilter(startDate = null))
            }
            DateFilterRow("По", endDate, dateFormatter, { onShowEndDatePicker(true) }) {
                onIntent(DoctorScreenIntent.UpdateTempFilter(endDate = null))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onIntent(DoctorScreenIntent.ResetFilters) }) { Text("Сбросить") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onIntent(
                            DoctorScreenIntent.ApplyFilters(
                                phone = phone,
                                name = name,
                                minProbability = 0,
                                startDate = startDate,
                                endDate = endDate
                            )
                        )
                        onIntent(DoctorScreenIntent.HideFilterSheet)
                    },
                    modifier = Modifier.testTag("doctor_filter_apply")
                ) { Text("Применить") }
            }
        }
    }

    if (showStartDatePicker) {
        AssessmentDatePicker(
            initial = startDate,
            endOfDay = false,
            onSelect = { onIntent(DoctorScreenIntent.UpdateTempFilter(startDate = it)) },
            onDismiss = { onShowStartDatePicker(false) }
        )
    }
    if (showEndDatePicker) {
        AssessmentDatePicker(
            initial = endDate,
            endOfDay = true,
            onSelect = { onIntent(DoctorScreenIntent.UpdateTempFilter(endDate = it)) },
            onDismiss = { onShowEndDatePicker(false) }
        )
    }
}

@Composable
private fun DateFilterRow(
    label: String,
    value: java.time.LocalDateTime?,
    formatter: DateTimeFormatter,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ${value?.format(formatter) ?: "Не выбрано"}", modifier = Modifier.weight(1f))
        Button(onClick = onPick) { Text("Выбрать") }
        if (value != null) {
            IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "Очистить") }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssessmentDatePicker(
    initial: java.time.LocalDateTime?,
    endOfDay: Boolean,
    onSelect: (java.time.LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: System.currentTimeMillis()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                    onSelect(
                        if (endOfDay) date.withHour(23).withMinute(59).withSecond(59)
                        else date.withHour(0).withMinute(0).withSecond(0)
                    )
                }
                onDismiss()
            }) { Text("ОК") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    ) {
        DatePicker(state = state, modifier = Modifier.padding(16.dp))
    }
}

private fun assessmentStatusLabel(status: String): String = when (status) {
    "INSUFFICIENT_EVIDENCE" -> "Недостаточно данных"
    "RANKED" -> "Есть ranking"
    "OUT_OF_SCOPE" -> "Вне области модели"
    "MODEL_UNAVAILABLE" -> "Модель недоступна"
    "ALL" -> "Все оценки"
    else -> status
}

private fun workflowLabel(status: String): String = when (status) {
    AssessmentWorkflow.NEW -> "Новое"
    AssessmentWorkflow.REVIEWED -> "Просмотрено"
    AssessmentWorkflow.CONTACT_REQUIRED -> "Нужно связаться"
    AssessmentWorkflow.CONTACTED -> "Связались"
    AssessmentWorkflow.CLOSED -> "Закрыто"
    "ALL" -> "Все статусы"
    else -> status
}
