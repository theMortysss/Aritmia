package my.diplom.aritmia.ui.screen.doctor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import my.diplom.aritmia.data.RuleEntity

/**
 * Shared editor used by the admin control center.
 *
 * The doctor workspace intentionally does not expose rule mutation. The composable remains in
 * this package for source compatibility with the existing admin import while rule ownership is
 * moved to the administrator role.
 */
@Composable
fun RuleEditorDialog(
    rule: RuleEntity?,
    onSave: (RuleEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var symptomKey by remember(rule?.id) { mutableStateOf(rule?.symptomKey.orEmpty()) }
    var medicalTerm by remember(rule?.id) { mutableStateOf(rule?.medicalTerm.orEmpty()) }
    var clarifyingQ by remember(rule?.id) { mutableStateOf(rule?.clarifyingQuestions.orEmpty()) }
    var answerTriggers by remember(rule?.id) { mutableStateOf(rule?.answerTriggers.orEmpty()) }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "Добавить правило" else "Редактировать правило") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = symptomKey,
                    onValueChange = { symptomKey = it },
                    label = { Text("Ключевая фраза") },
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
                    value = clarifyingQ,
                    onValueChange = { clarifyingQ = it },
                    label = { Text("Уточняющие вопросы (через ;)") },
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
                Text(
                    "Legacy-вес правила скрыт: он не влияет на текущий disease ranking и не редактируется врачом или администратором.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = symptomKey.isNotBlank() && medicalTerm.isNotBlank(),
                onClick = {
                    onSave(
                        RuleEntity(
                            id = rule?.id ?: 0,
                            symptomKey = symptomKey.trim(),
                            medicalTerm = medicalTerm.trim(),
                            probabilityWeight = rule?.probabilityWeight ?: 0,
                            clarifyingQuestions = clarifyingQ.trim().takeIf { it.isNotBlank() },
                            answerTriggers = answerTriggers.trim().takeIf { it.isNotBlank() }
                        )
                    )
                }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
