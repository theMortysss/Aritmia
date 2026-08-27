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
 * Legacy/admin rule questions stay first so existing answerTriggers keep their historical
 * indices. Stable ontology questions are appended rather than replaced: an editable rule must
 * never be able to suppress safety/triage context such as exertional syncope or extreme BP.
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

    val rulePrompts = if (rule != null && ruleQuestions.isNotEmpty()) {
        val triggerOptions = rule.answerTriggers
            ?.split(";")
            ?.mapNotNull { trigger -> trigger.substringBefore("=", missingDelimiterValue = "").trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
            .ifEmpty { listOf("да", "нет") }
        val options = (triggerOptions + "не могу ответить").distinct()
        ruleQuestions.mapIndexed { index, question ->
            ClarificationPrompt(
                id = "rule:${rule.id}:$index",
                text = question,
                options = options
            )
        }
    } else {
        emptyList()
    }

    val extraction = FreeTextSymptomExtractor.extract(listOf(symptom))
    val conceptPrompts = extraction.conceptIds
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

    return (rulePrompts + conceptPrompts).distinctBy { it.id }
}

fun hasClarificationQuestions(
    symptoms: List<String>,
    rules: List<RuleEntity>
): Boolean = symptoms.any { clarificationPromptsFor(it, rules).isNotEmpty() }
