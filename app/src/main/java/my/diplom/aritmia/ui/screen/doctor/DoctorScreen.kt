package my.diplom.aritmia.ui.screen.doctor

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.ui.composable.TopBar
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
    val focusManager: FocusManager = LocalFocusManager.current
    val tabs = listOf("Пациенты", "Правила")
    val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.state.collect { s -> if (s.logout) onLogout() }
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
            TabRow(selectedTabIndex = state.selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        text = { Text(title) },
                        selected = state.selectedTabIndex == index,
                        onClick = { viewModel.onIntent(DoctorScreenIntent.ChangeTab(index)) }
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = { viewModel.onIntent(DoctorScreenIntent.ShowFilterSheet) }) {
                                Text("Фильтры")
                            }
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                val summary = buildFilterSummary(
                                    state.phoneFilter,
                                    state.nameFilter,
                                    state.startDate,
                                    state.endDate,
                                    dateFormatter
                                )
                                if (summary.isNotEmpty()) {
                                    Text(
                                        "Фильтры:",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                } else {
                                    Text("Фильтры не заданы", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        } else if (state.symptoms.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    if (state.phoneFilter.isBlank() && state.nameFilter.isBlank() &&
                                        state.startDate == null && state.endDate == null
                                    ) {
                                        "Симптомы пациентов отсутствуют"
                                    } else {
                                        "Симптомы по заданным фильтрам не найдены"
                                    },
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                )
                            }
                        } else {
                            LazyColumn {
                                items(state.symptoms) { item ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        elevation = CardDefaults.cardElevation(4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(
                                                "${item.user?.fullName ?: "Неизвестно"}\n(${item.user?.phone})",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text("Дата ввода: ${item.symptom.createdAt.format(dateFormatter)}")
                                            Spacer(Modifier.height(8.dp))

                                            Text("Начальные симптомы:")
                                            item.symptom.userInput.split(". ")
                                                .filter { it.isNotBlank() }
                                                .forEach { Text("- $it") }

                                            if (item.recognizedMedicalTerms.isNotEmpty()) {
                                                Spacer(Modifier.height(8.dp))
                                                Text("Распознанные симптомы:")
                                                item.recognizedMedicalTerms.forEach { Text("- $it") }
                                            }

                                            if (item.unrecognizedSymptoms.isNotEmpty()) {
                                                Spacer(Modifier.height(8.dp))
                                                Text("Нераспознанные симптомы:")
                                                item.unrecognizedSymptoms.forEach { Text("- $it") }
                                            }

                                            if (item.clarifyingAnswers.isNotEmpty()) {
                                                Spacer(Modifier.height(8.dp))
                                                Text("Ответы на уточняющие вопросы:")
                                                item.clarifyingAnswers.forEach { (symptom, answers) ->
                                                    answers.forEach { Text("- $symptom: $it") }
                                                }
                                            }

                                            Spacer(Modifier.height(8.dp))

                                            if (item.user != null) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Checkbox(
                                                        checked = item.symptom.calledByDoctor,
                                                        onCheckedChange = { checked ->
                                                            viewModel.onIntent(
                                                                DoctorScreenIntent.MarkPatientAsCalled(
                                                                    item.symptom.id,
                                                                    checked
                                                                )
                                                            )
                                                        }
                                                    )
                                                    Text(
                                                        "С пациентом связались",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.padding(start = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                    ) {
                        Button(onClick = {
                            viewModel.onIntent(DoctorScreenIntent.SelectRule(null))
                            viewModel.onIntent(DoctorScreenIntent.ShowRuleEditor)
                        }) { Text("Добавить правило") }

                        Spacer(Modifier.height(16.dp))

                        LazyColumn {
                            items(state.rules) { rule ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("Ключ: ${rule.symptomKey}")
                                        Text("Термин: ${rule.medicalTerm}")
                                        Text("Вес: ${rule.probabilityWeight}")
                                        Text("Вопросы: ${rule.clarifyingQuestions ?: "Нет"}")
                                        Text("Триггеры: ${rule.answerTriggers ?: "Нет"}")
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Button(onClick = {
                                                viewModel.onIntent(DoctorScreenIntent.SelectRule(rule))
                                                viewModel.onIntent(DoctorScreenIntent.ShowRuleEditor)
                                            }) { Text("Редактировать") }
                                            Button(onClick = {
                                                viewModel.onIntent(DoctorScreenIntent.DeleteRule(rule))
                                            }) { Text("Удалить") }
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
                                viewModel.onIntent(DoctorScreenIntent.SaveRule(rule))
                                viewModel.onIntent(DoctorScreenIntent.HideRuleEditor)
                            },
                            onDismiss = { viewModel.onIntent(DoctorScreenIntent.HideRuleEditor) }
                        )
                    }
                }
            }
        }

        if (state.showFilterSheet) {
            ModalBottomSheet(
                contentWindowInsets = { WindowInsets.navigationBars },
                onDismissRequest = { viewModel.onIntent(DoctorScreenIntent.HideFilterSheet) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Фильтры",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = state.tempPhoneFilter,
                        onValueChange = { viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(phone = it)) },
                        label = { Text("Телефон (только цифры)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = state.tempNameFilter,
                        onValueChange = { viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(name = it)) },
                        label = { Text("ФИО") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "С: ${state.tempStartDate?.format(dateFormatter) ?: "Не выбрано"}",
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { showStartDatePicker = true }) { Text("Выбрать") }
                        if (state.tempStartDate != null) {
                            IconButton(onClick = {
                                viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(startDate = null))
                            }) { Icon(Icons.Default.Clear, "Очистить") }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "По: ${state.tempEndDate?.format(dateFormatter) ?: "Не выбрано"}",
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { showEndDatePicker = true }) { Text("Выбрать") }
                        if (state.tempEndDate != null) {
                            IconButton(onClick = {
                                viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(endDate = null))
                            }) { Icon(Icons.Default.Clear, "Очистить") }
                        }
                    }

                    if (showStartDatePicker) {
                        DatePickerDialog(
                            onDismissRequest = { showStartDatePicker = false },
                            confirmButton = {
                                TextButton(onClick = { showStartDatePicker = false }) { Text("ОК") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showStartDatePicker = false }) { Text("Отмена") }
                            }
                        ) {
                            val dpState = rememberDatePickerState(
                                initialSelectedDateMillis = state.tempStartDate
                                    ?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
                                    ?: System.currentTimeMillis()
                            )
                            DatePicker(state = dpState, modifier = Modifier.padding(16.dp))
                            LaunchedEffect(dpState.selectedDateMillis) {
                                dpState.selectedDateMillis?.let { ms ->
                                    viewModel.onIntent(
                                        DoctorScreenIntent.UpdateTempFilter(
                                            startDate = Instant.ofEpochMilli(ms)
                                                .atZone(ZoneId.systemDefault()).toLocalDateTime()
                                                .withHour(0).withMinute(0)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    if (showEndDatePicker) {
                        DatePickerDialog(
                            onDismissRequest = { showEndDatePicker = false },
                            confirmButton = {
                                TextButton(onClick = { showEndDatePicker = false }) { Text("ОК") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showEndDatePicker = false }) { Text("Отмена") }
                            }
                        ) {
                            val dpState = rememberDatePickerState(
                                initialSelectedDateMillis = state.tempEndDate
                                    ?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
                                    ?: System.currentTimeMillis()
                            )
                            DatePicker(state = dpState, modifier = Modifier.padding(16.dp))
                            LaunchedEffect(dpState.selectedDateMillis) {
                                dpState.selectedDateMillis?.let { ms ->
                                    viewModel.onIntent(
                                        DoctorScreenIntent.UpdateTempFilter(
                                            endDate = Instant.ofEpochMilli(ms)
                                                .atZone(ZoneId.systemDefault()).toLocalDateTime()
                                                .withHour(23).withMinute(59)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { viewModel.onIntent(DoctorScreenIntent.ResetFilters) }) {
                            Text("Сбросить")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            viewModel.onIntent(
                                DoctorScreenIntent.ApplyFilters(
                                    phone = state.tempPhoneFilter,
                                    name = state.tempNameFilter,
                                    minProbability = 0,
                                    startDate = state.tempStartDate,
                                    endDate = state.tempEndDate
                                )
                            )
                            viewModel.onIntent(DoctorScreenIntent.HideFilterSheet)
                        }) { Text("Применить") }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun buildFilterSummary(
    phoneFilter: String,
    nameFilter: String,
    startDate: java.time.LocalDateTime?,
    endDate: java.time.LocalDateTime?,
    dateFormatter: DateTimeFormatter
): List<String> {
    val filters = mutableListOf<String>()
    if (phoneFilter.isNotBlank()) filters.add("телефон=$phoneFilter")
    if (nameFilter.isNotBlank()) filters.add("ФИО=$nameFilter")
    if (startDate != null) filters.add("с ${startDate.format(dateFormatter)}")
    if (endDate != null) filters.add("по ${endDate.format(dateFormatter)}")
    return if (filters.size > 3) filters.take(3) + listOf("и ещё ${filters.size - 3}...")
    else filters
}

@Composable
fun RuleEditorDialog(
    rule: RuleEntity?,
    onSave: (RuleEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var symptomKey by remember { mutableStateOf(rule?.symptomKey ?: "") }
    var medicalTerm by remember { mutableStateOf(rule?.medicalTerm ?: "") }
    var probabilityWeight by remember { mutableStateOf(rule?.probabilityWeight?.toString() ?: "") }
    var clarifyingQ by remember { mutableStateOf(rule?.clarifyingQuestions ?: "") }
    var answerTriggers by remember { mutableStateOf(rule?.answerTriggers ?: "") }

    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "Добавить правило" else "Редактировать правило") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { focusManager.clearFocus() }
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = symptomKey,
                    onValueChange = { symptomKey = it },
                    label = { Text("Ключевое слово") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                OutlinedTextField(
                    value = medicalTerm,
                    onValueChange = { medicalTerm = it },
                    label = { Text("Медицинский термин") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                OutlinedTextField(
                    value = probabilityWeight,
                    onValueChange = { probabilityWeight = it.filter { c -> c.isDigit() } },
                    label = { Text("Вес правила (0–100)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                OutlinedTextField(
                    value = clarifyingQ,
                    onValueChange = { clarifyingQ = it },
                    label = { Text("Уточняющие вопросы (разделяйте ;)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                OutlinedTextField(
                    value = answerTriggers,
                    onValueChange = { answerTriggers = it },
                    label = { Text("Триггеры (ответ=термин;...)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                focusManager.clearFocus()
                onSave(
                    RuleEntity(
                        id = rule?.id ?: 0,
                        symptomKey = symptomKey,
                        medicalTerm = medicalTerm,
                        probabilityWeight = probabilityWeight.toIntOrNull() ?: 0,
                        clarifyingQuestions = clarifyingQ.takeIf { it.isNotBlank() },
                        answerTriggers = answerTriggers.takeIf { it.isNotBlank() }
                    )
                )
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
