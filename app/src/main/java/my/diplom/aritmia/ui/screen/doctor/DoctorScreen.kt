package my.diplom.aritmia.ui.screen.doctor

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.ui.composable.TopBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.ui.screen.doctor.model.DoctorScreenIntent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar

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
        viewModel.state.collect { state ->
            if (state.logout) {
                onLogout()
            }
        }
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
                            Button(
                                modifier = Modifier,
                                onClick = { viewModel.onIntent(DoctorScreenIntent.ShowFilterSheet) }
                            ) {
                                Text("Фильтры")
                            }
                            Column(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                            ) {
                                val filterSummary = buildFilterSummary(
                                    state.phoneFilter,
                                    state.nameFilter,
                                    state.minProbability,
                                    state.startDate,
                                    state.endDate,
                                    dateFormatter
                                )
                                if (filterSummary.isNotEmpty()) {
                                    Text(
                                        text = "Фильтры:",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    filterSummary.forEach { filter ->
                                        Text(
                                            text = "$filter",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Фильтры не заданы",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        } else if (state.symptoms.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (state.phoneFilter.isBlank() && state.nameFilter.isBlank() && state.minProbability == 0 && state.startDate == null && state.endDate == null) {
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
                                items(state.symptoms) { symptomItem ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${symptomItem.user?.fullName ?: "Неизвестно"}\n(${symptomItem.user?.phone})",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))

                                            Text(
                                                text = "Дата ввода: ${symptomItem.symptom.createdAt.format(dateFormatter)}"
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))

                                            Text(text = "Начальные симптомы:")
                                            symptomItem.symptom.userInput.split(". ")
                                                .filter { it.isNotBlank() }.forEach { symptom ->
                                                    Text(text = "- $symptom")
                                                }

                                            if (symptomItem.recognizedMedicalTerms.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(text = "Распознанные симптомы:")
                                                symptomItem.recognizedMedicalTerms.forEach { term ->
                                                    Text(text = "- $term")
                                                }
                                            }

                                            if (symptomItem.unrecognizedSymptoms.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(text = "Нераспознанные симптомы:")
                                                symptomItem.unrecognizedSymptoms.forEach { symptom ->
                                                    Text(text = "- $symptom")
                                                }
                                            }

                                            if (symptomItem.clarifyingAnswers.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(text = "Ответы на уточняющие вопросы:")
                                                symptomItem.clarifyingAnswers.forEach { (symptom, answers) ->
                                                    answers.forEachIndexed { index, answer ->
                                                        Text(text = "- $symptom: $answer")
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Text(text = "Вероятность: ${symptomItem.symptom.probability}%")

                                            Spacer(modifier = Modifier.height(8.dp))

                                            if (symptomItem.user != null) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Checkbox(
                                                        checked = symptomItem.symptom.calledByDoctor,
                                                        onCheckedChange = { checked ->
                                                            viewModel.onIntent(
                                                                DoctorScreenIntent.MarkPatientAsCalled(
                                                                    symptomId = symptomItem.symptom.id,
                                                                    called = checked
                                                                )
                                                            )
                                                        }
                                                    )
                                                    Text(
                                                        text = "С пациентом связались",
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Button(onClick = {
                            viewModel.onIntent(DoctorScreenIntent.SelectRule(null))
                            viewModel.onIntent(DoctorScreenIntent.ShowRuleEditor)
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
                                                viewModel.onIntent(DoctorScreenIntent.SelectRule(rule))
                                                viewModel.onIntent(DoctorScreenIntent.ShowRuleEditor)
                                            }) {
                                                Text("Редактировать")
                                            }
                                            Button(onClick = { viewModel.onIntent(DoctorScreenIntent.DeleteRule(rule)) }) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Фильтры",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = state.tempPhoneFilter,
                        onValueChange = { viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(phone = it)) },
                        label = { Text("Фильтр по номеру телефона (только цифры)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.tempNameFilter,
                        onValueChange = { viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(name = it)) },
                        label = { Text("Фильтр по ФИО") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Дата с: ${state.tempStartDate?.format(dateFormatter) ?: "Не выбрано"}",
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { showStartDatePicker = true }
                        ) {
                            Text("Выбрать")
                        }
                        if (state.tempStartDate != null) {
                            IconButton(onClick = {
                                viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(startDate = null))
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить дату начала")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Дата по: ${state.tempEndDate?.format(dateFormatter) ?: "Не выбрано"}",
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { showEndDatePicker = true }
                        ) {
                            Text("Выбрать")
                        }
                        if (state.tempEndDate != null) {
                            IconButton(onClick = {
                                viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(endDate = null))
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить дату окончания")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (showStartDatePicker) {
                        DatePickerDialog(
                            onDismissRequest = { showStartDatePicker = false },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showStartDatePicker = false
                                    }
                                ) {
                                    Text("ОК")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showStartDatePicker = false }
                                ) {
                                    Text("Отмена")
                                }
                            }
                        ) {
                            val datePickerState = rememberDatePickerState(
                                initialSelectedDateMillis = state.tempStartDate?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
                                    ?: System.currentTimeMillis()
                            )
                            DatePicker(
                                state = datePickerState,
                                modifier = Modifier.padding(16.dp)
                            )

                            LaunchedEffect(datePickerState.selectedDateMillis) {
                                datePickerState.selectedDateMillis?.let { millis ->
                                    val newDate = Instant.ofEpochMilli(millis)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDateTime()
                                        .withHour(0)
                                        .withMinute(0)
                                    viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(startDate = newDate))
                                }
                            }
                        }
                    }

                    if (showEndDatePicker) {
                        DatePickerDialog(
                            onDismissRequest = { showEndDatePicker = false },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showEndDatePicker = false
                                    }
                                ) {
                                    Text("ОК")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showEndDatePicker = false }
                                ) {
                                    Text("Отмена")
                                }
                            }
                        ) {
                            val datePickerState = rememberDatePickerState(
                                initialSelectedDateMillis = state.tempEndDate?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
                                    ?: System.currentTimeMillis()
                            )
                            DatePicker(
                                state = datePickerState,
                                modifier = Modifier.padding(16.dp)
                            )

                            LaunchedEffect(datePickerState.selectedDateMillis) {
                                datePickerState.selectedDateMillis?.let { millis ->
                                    val newDate = Instant.ofEpochMilli(millis)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDateTime()
                                        .withHour(23)
                                        .withMinute(59)
                                    viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(endDate = newDate))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Мин. вероятность: ${state.tempMinProbability}%")
                        Slider(
                            value = state.tempMinProbability.toFloat(),
                            onValueChange = { viewModel.onIntent(DoctorScreenIntent.UpdateTempFilter(minProbability = it.toInt())) },
                            valueRange = 0f..100f,
                            steps = 100,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { viewModel.onIntent(DoctorScreenIntent.ResetFilters) }) {
                            Text("Сбросить")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            viewModel.onIntent(
                                DoctorScreenIntent.ApplyFilters(
                                    phone = state.tempPhoneFilter,
                                    name = state.tempNameFilter,
                                    minProbability = state.tempMinProbability,
                                    startDate = state.tempStartDate,
                                    endDate = state.tempEndDate
                                )
                            )
                            viewModel.onIntent(DoctorScreenIntent.HideFilterSheet)
                        }) {
                            Text("Применить")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
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
    minProbability: Int,
    startDate: LocalDateTime?,
    endDate: LocalDateTime?,
    dateFormatter: DateTimeFormatter
): List<String> {
    val filters = mutableListOf<String>()
    if (phoneFilter.isNotBlank()) filters.add("телефон=$phoneFilter")
    if (nameFilter.isNotBlank()) filters.add("ФИО=$nameFilter")
    if (minProbability > 0) filters.add("вероятность>$minProbability")
    if (startDate != null) filters.add("с ${startDate.format(dateFormatter)}")
    if (endDate != null) filters.add("по ${endDate.format(dateFormatter)}")
    return if (filters.size > 3) {
        filters.take(3) + "и ещё ${filters.size - 3}..."
    } else {
        filters
    }
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
    var clarifyingQuestions by remember { mutableStateOf(rule?.clarifyingQuestions ?: "") }
    var answerTriggers by remember { mutableStateOf(rule?.answerTriggers ?: "") }

    val focusManager: FocusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = { onDismiss() },
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
                    )
            ) {
                OutlinedTextField(
                    value = symptomKey,
                    onValueChange = { symptomKey = it },
                    label = { Text("Ключевое слово") },
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
                    value = medicalTerm,
                    onValueChange = { medicalTerm = it },
                    label = { Text("Медицинский термин") },
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
                    value = probabilityWeight,
                    onValueChange = { probabilityWeight = it.filter { char -> char.isDigit() } },
                    label = { Text("Вес вероятности") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = clarifyingQuestions,
                    onValueChange = { clarifyingQuestions = it },
                    label = { Text("Уточняющие вопросы (разделяйте ;)") },
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
                    value = answerTriggers,
                    onValueChange = { answerTriggers = it },
                    label = { Text("Триггеры ответов (ключ=термин;...)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSave(
                        RuleEntity(
                            id = rule?.id ?: 0,
                            symptomKey = symptomKey,
                            medicalTerm = medicalTerm,
                            probabilityWeight = probabilityWeight.toIntOrNull() ?: 0,
                            clarifyingQuestions = clarifyingQuestions.takeIf { it.isNotBlank() },
                            answerTriggers = answerTriggers.takeIf { it.isNotBlank() }
                        )
                    )
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            Button(onClick = { onDismiss() }) {
                Text("Отмена")
            }
        }
    )
}


