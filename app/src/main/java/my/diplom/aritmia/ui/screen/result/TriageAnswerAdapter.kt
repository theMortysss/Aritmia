package my.diplom.aritmia.ui.screen.result

import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.ui.screen.clarify.clarificationPromptsFor

/**
 * Stored clarification answers remain symptom-indexed for backward compatibility.
 * The deterministic triage layer consumes stable prompt ids, so map the current prompt order
 * back to ids without changing the Room schema or historical records.
 */
fun triageAnswersFor(
    symptoms: List<String>,
    rules: List<RuleEntity>,
    storedAnswers: Map<String, List<String>>
): Map<String, String> = buildMap {
    symptoms.forEach { symptom ->
        val answers = storedAnswers[symptom].orEmpty()
        clarificationPromptsFor(symptom, rules).forEachIndexed { index, prompt ->
            answers.getOrNull(index)
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("не могу ответить", ignoreCase = true) }
                ?.let { answer -> put(prompt.id, answer) }
        }
    }
}
