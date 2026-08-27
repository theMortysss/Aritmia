package my.diplom.aritmia.ui.screen.clarify

import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.diagnosis.ComplaintOntology
import my.diplom.aritmia.diagnosis.FreeTextSymptomExtractor

data class ClarificationPrompt(
    val id: String,
    val text: String,
    val options: List<String>
)

/**
 * Admin rules are explicit overrides. If a matching rule contains clarification
 * questions, preserve its existing behavior. Otherwise fall back to stable concept-level
 * prompts from ComplaintOntology, so different Russian aliases of the same complaint
 * receive the same clarification flow.
 */
fun clarificationPromptsFor(
    symptom: String,
    rules: List<RuleEntity>
): List<ClarificationPrompt> {
    val rule = rules.find { symptom.contains(it.symptomKey, ignoreCase = true) }
    val ruleQuestions = rule?.clarifyingQuestions
        ?.split(";")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    if (rule != null && ruleQuestions.isNotEmpty()) {
        val triggerOptions = rule.answerTriggers
            ?.split(";")
            ?.mapNotNull { trigger -> trigger.substringBefore("=", missingDelimiterValue = "").trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
            .ifEmpty { listOf("да", "нет") }
        val options = (triggerOptions + "не могу ответить").distinct()
        return ruleQuestions.mapIndexed { index, question ->
            ClarificationPrompt(
                id = "rule:${rule.id}:$index",
                text = question,
                options = options
            )
        }
    }

    val extraction = FreeTextSymptomExtractor.extract(listOf(symptom))
    return extraction.conceptIds
        .mapNotNull(ComplaintOntology::concept)
        .flatMap { concept ->
            concept.clarifyingQuestions.map { question ->
                ClarificationPrompt(
                    id = "concept:${concept.id}:${question.id}",
                    text = question.text,
                    options = (question.options + "не могу ответить").distinct()
                )
            }
        }
        .distinctBy { it.id }
}

fun hasClarificationQuestions(
    symptoms: List<String>,
    rules: List<RuleEntity>
): Boolean = symptoms.any { clarificationPromptsFor(it, rules).isNotEmpty() }
