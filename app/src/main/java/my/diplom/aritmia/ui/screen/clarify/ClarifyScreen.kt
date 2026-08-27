package my.diplom.aritmia.ui.screen.clarify

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
            val answers = state.answers
            viewModel.onIntent(ClarifyScreenIntent.FinishNavigationHandled)
            onFinish(answers)
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
                item {
                    OutlinedButton(
                        onClick = {
                            state.symptoms.forEach { symptom ->
                                val prompts = clarificationPromptsFor(symptom, state.rules)
                                val answers = state.answers[symptom].orEmpty()
                                prompts.indices.forEach { index ->
                                    if (answers.getOrNull(index).isNullOrBlank()) {
                                        viewModel.onIntent(
                                            ClarifyScreenIntent.UpdateAnswer(
                                                symptom,
                                                index,
                                                "не могу ответить"
                                            )
                                        )
                                    }
                                }
                            }
                            viewModel.onIntent(ClarifyScreenIntent.Finish)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Не могу ответить — продолжить")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                items(state.symptoms) { symptom ->
                    val prompts = clarificationPromptsFor(symptom, state.rules)
                    val symptomAnswers = state.answers[symptom] ?: mutableListOf()

                    prompts.forEachIndexed { index, prompt ->
                        val answer = symptomAnswers.getOrNull(index) ?: ""

                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text("Симптом: $symptom")
                            Text(prompt.text)
                            Spacer(Modifier.height(4.dp))
                            Column(modifier = Modifier.fillMaxWidth()) {
                                prompt.options.forEach { option ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        RadioButton(
                                            selected = answer == option,
                                            onClick = {
                                                viewModel.onIntent(
                                                    ClarifyScreenIntent.UpdateAnswer(
                                                        symptom, index, option
                                                    )
                                                )
                                            },
                                            modifier = Modifier.testTag("clarify_option_$option")
                                        )
                                        Text(
                                            text = option.replaceFirstChar { it.uppercase() },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))

                    val allAnswered = state.symptoms.all { symptom ->
                        val prompts = clarificationPromptsFor(symptom, state.rules)
                        val answers = state.answers[symptom] ?: emptyList()
                        prompts.indices.all { index -> answers.getOrNull(index)?.isNotBlank() == true }
                    }

                    Button(
                        onClick = { viewModel.onIntent(ClarifyScreenIntent.Finish) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = allAnswered
                    ) {
                        Text("Завершить")
                    }
                }
            }
        }
    }
}
