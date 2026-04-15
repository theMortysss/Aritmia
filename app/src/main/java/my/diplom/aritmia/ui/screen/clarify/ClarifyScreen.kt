package my.diplom.aritmia.ui.screen.clarify

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import my.diplom.aritmia.ui.composable.TopBar
import my.diplom.aritmia.ui.screen.clarify.model.ClarifyScreenIntent

@Composable
fun ClarifyScreen(
    symptoms: List<String>,
    userId: Int,
    initialAnswers: Map<String, List<String>>,
    onFinish: (answers: Map<String, List<String>>) -> Unit,
    onLogout: () -> Unit,
    viewModel: ClarifyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.onIntent(ClarifyScreenIntent.Initialize(symptoms, userId, initialAnswers))
    }

    LaunchedEffect(state.navigateToFinish) {
        if (state.navigateToFinish) {
            onFinish(state.answers)
        }
    }

    LaunchedEffect(state.logout) {
        if (state.logout) onLogout()
    }

    Scaffold(
        topBar = { TopBar(onLogout = { viewModel.onIntent(ClarifyScreenIntent.Logout) }) }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                items(state.symptoms) { symptom ->
                    val rule      = state.rules.find { symptom.contains(it.symptomKey, ignoreCase = true) }
                    val questions = rule?.clarifyingQuestions?.split(";")?.filter { it.isNotBlank() }
                        ?: emptyList()
                    val symptomAnswers = state.answers[symptom] ?: mutableListOf()

                    if (questions.isNotEmpty()) {
                        questions.forEachIndexed { index, question ->
                            val answer = symptomAnswers.getOrNull(index) ?: ""

                            val answerOptions = (rule?.answerTriggers?.split(";")
                                ?.filter { it.isNotBlank() }
                                ?.mapNotNull { it.split("=").firstOrNull() }
                                ?: listOf("да", "нет")).toMutableList()
                            answerOptions.add("не могу ответить")

                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text("Симптом: $symptom")
                                Text(question)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentWidth(Alignment.CenterHorizontally),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    answerOptions.forEach { option ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier          = Modifier.padding(horizontal = 8.dp)
                                        ) {
                                            RadioButton(
                                                selected = answer == option,
                                                onClick  = {
                                                    viewModel.onIntent(
                                                        ClarifyScreenIntent.UpdateAnswer(
                                                            symptom, index, option
                                                        )
                                                    )
                                                }
                                            )
                                            Text(
                                                text     = option.replaceFirstChar { it.uppercase() },
                                                modifier = Modifier.align(Alignment.CenterVertically)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))

                    val allAnswered = state.symptoms.all { symptom ->
                        val rule      = state.rules.find { symptom.contains(it.symptomKey, ignoreCase = true) }
                        val questions = rule?.clarifyingQuestions?.split(";")
                            ?.filter { it.isNotBlank() } ?: emptyList()
                        val answers   = state.answers[symptom] ?: emptyList()
                        questions.size == answers.size && answers.all { it.isNotBlank() }
                    }

                    Button(
                        onClick  = { viewModel.onIntent(ClarifyScreenIntent.Finish) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled  = allAnswered
                    ) {
                        Text("Завершить")
                    }
                }
            }
        }
    }
}
