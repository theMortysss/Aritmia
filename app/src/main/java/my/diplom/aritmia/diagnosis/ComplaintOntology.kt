package my.diplom.aritmia.diagnosis

/**
 * A clarification prompt belongs to a stable complaint concept, not to one exact
 * wording entered by the patient. Clarification answers are context only: they are
 * not appended to the pretrained MLP feature vector.
 */
data class ComplaintClarifyingQuestion(
    val id: String,
    val text: String,
    val options: List<String> = listOf("да", "нет")
)

data class ComplaintConcept(
    val id: String,
    val label: String,
    val aliases: List<String>,
    /** The corresponding pretrained v2 MLP feature, or null for context-only concepts. */
    val modelConceptId: String? = null,
    /** Original English feature names used to trace additions back to the source matrix. */
    val sourceFeatures: List<String> = emptyList(),
    val clarifyingQuestions: List<ComplaintClarifyingQuestion> = emptyList()
)

/**
 * Patient-facing complaint ontology.
 *
 * DiseaseCatalog.concepts remains the immutable 47-feature contract of disease_model.json.
 * This ontology may be broader: context-only concepts can be recognized, suggested and
 * clarified without pretending that the existing MLP was trained on them.
 */
object ComplaintOntology {

    private fun q(
        id: String,
        text: String,
        options: List<String> = listOf("да", "нет")
    ) = ComplaintClarifyingQuestion(id, text, options)

    private val extraModelAliases: Map<String, List<String>> = mapOf(
        "chest_pain" to listOf("боли в груди", "грудная боль", "болит за грудиной"),
        "dyspnea" to listOf(
            "нехватка воздуха",
            "нехватки воздуха",
            "трудно дышать",
            "дыхание затруднено",
            "задыхаюсь при ходьбе",
            "одышка лежа",
            "одышка в покое"
        ),
        "syncope" to listOf(
            "потерял сознание",
            "потеряла сознание",
            "терял сознание",
            "теряла сознание",
            "отключился",
            "отключилась"
        ),
        "pleuritic_pain" to listOf(
            "когда вдыхаю боль в груди усиливается",
            "при вдохе боль усиливается",
            "боль усиливается на вдохе"
        ),
        "abnormal_breathing_sounds" to listOf(
            "дыхание со свистом",
            "свист в груди",
            "свист при выдохе",
            "хриплю"
        ),
        "phlegm" to listOf("кашляю с мокротой", "откашливаю мокроту"),
        "edema" to listOf("опухли лодыжки", "опухли ноги", "отекли ноги", "отекли стопы"),
        "fast_heart_rate" to listOf("пульс выше 100", "пульс больше 100"),
        "slow_heart_rate" to listOf("пульс ниже 60", "пульс меньше шестидесяти")
    )

    private val defaultQuestions: Map<String, List<ComplaintClarifyingQuestion>> = mapOf(
        "chest_pain" to listOf(
            q("sudden_onset", "Боль в груди возникла внезапно?"),
            q("present_now", "Боль сохраняется сейчас?")
        ),
        "chest_pressure" to listOf(
            q("exertional", "Давление или сдавление в груди возникает при физической нагрузке?"),
            q("present_now", "Сдавление в груди сохраняется сейчас?")
        ),
        "chest_tightness" to listOf(
            q("exertional", "Стеснение в груди связано с физической нагрузкой?")
        ),
        "dyspnea" to listOf(
            q("at_rest", "Одышка возникает в покое?"),
            q("sudden_onset", "Нехватка воздуха появилась внезапно?")
        ),
        "syncope" to listOf(
            q("during_exertion", "Потеря сознания произошла во время физической нагрузки?"),
            q("while_supine", "Потеря сознания произошла лёжа или во сне?"),
            q("preceded_by_palpitations", "Непосредственно перед потерей сознания было сильное сердцебиение или перебои?")
        ),
        "palpitations" to listOf(
            q("sudden_onset", "Сердцебиение начинается внезапно?"),
            q("at_rest", "Сердцебиение возникает в покое?")
        ),
        "irregular_heartbeat" to listOf(
            q("current", "Перебои или нерегулярный ритм ощущаются сейчас?")
        ),
        "fast_heart_rate" to listOf(
            q(
                "measured_rate",
                "Удалось измерить пульс во время эпизода?",
                listOf("выше 120 в минуту", "100–120 в минуту", "ниже 100 в минуту", "не измерял(а) / не помню")
            )
        ),
        "slow_heart_rate" to listOf(
            q(
                "measured_rate",
                "Удалось измерить пульс во время эпизода?",
                listOf("ниже 40 в минуту", "40–59 в минуту", "60 и выше", "не измерял(а) / не помню")
            )
        ),
        "high_bp" to listOf(
            q(
                "measured_bp",
                "Какое давление было при измерении?",
                listOf("180/120 или выше", "140–179 / 90–119", "ниже 140/90", "не измерял(а) / не помню")
            )
        ),
        "dizziness" to listOf(
            q("on_standing", "Головокружение появляется после вставания?")
        ),
        "edema" to listOf(
            q("both_legs", "Отёки появились на обеих ногах?"),
            q("rapid_onset", "Отёки заметно усилились за последние несколько дней?")
        ),
        "hemoptysis" to listOf(
            q("current", "Кровь в мокроте появилась сегодня или сохраняется сейчас?")
        ),
        "back_pain" to listOf(
            q("sudden_severe", "Боль в спине возникла внезапно и сразу была сильной?")
        ),
        "abdominal_pain" to listOf(
            q("sudden_severe", "Боль в животе возникла внезапно и сразу была сильной?")
        ),
        "leg_pain" to listOf(
            q("one_side", "Боль выражена преимущественно в одной ноге?")
        ),
        "sweating" to listOf(
            q("cold_sweat", "Это холодный липкий пот?")
        ),
        "nausea" to listOf(
            q("with_chest_discomfort", "Тошнота появилась одновременно с дискомфортом в груди?")
        )
    )

    private val modelConcepts: List<ComplaintConcept> = DiseaseCatalog.concepts.map { concept ->
        ComplaintConcept(
            id = concept.id,
            label = concept.label,
            aliases = (concept.aliases + extraModelAliases[concept.id].orEmpty()).distinct(),
            modelConceptId = concept.id,
            clarifyingQuestions = defaultQuestions[concept.id].orEmpty()
        )
    }

    private val expandedConcepts: List<ComplaintConcept> = listOf(
        ComplaintConcept(
            id = "low_bp",
            label = "Пониженное артериальное давление",
            aliases = listOf(
                "низкое давление",
                "пониженное давление",
                "давление упало",
                "давление падает",
                "гипотония",
                "артериальная гипотония",
                "давление ниже 90 на 60",
                "давление ниже 90/60",
                "низкого давления"
            ),
            sourceFeatures = listOf("clinical context: hypotension"),
            clarifyingQuestions = listOf(
                q(
                    "measured_bp",
                    "Какое давление было при измерении?",
                    listOf("ниже 90/60", "90/60 или выше", "не измерял(а) / не помню")
                ),
                q("symptomatic", "На фоне низкого давления самочувствие заметно ухудшается?")
            )
        ),
        ComplaintConcept(
            id = "visual_disturbance",
            label = "Нарушение зрения",
            aliases = listOf(
                "мутно вижу",
                "затуманилось зрение",
                "затуманенное зрение",
                "расплывается перед глазами",
                "нечеткое зрение",
                "нечёткое зрение",
                "пелена перед глазами",
                "резко ухудшилось зрение"
            ),
            sourceFeatures = listOf("diminished vision", "spots or clouds in vision"),
            clarifyingQuestions = listOf(
                q("sudden_onset", "Нарушение зрения возникло внезапно?"),
                q("one_eye", "Нарушение зрения выражено только в одном глазу?")
            )
        ),
        ComplaintConcept(
            id = "confusion",
            label = "Спутанность сознания",
            aliases = listOf(
                "спутанность сознания",
                "сознание спутано",
                "дезориентация",
                "не понимаю где нахожусь",
                "стал путаться",
                "стала путаться"
            ),
            sourceFeatures = listOf("clinical context: confusion"),
            clarifyingQuestions = listOf(
                q("sudden_onset", "Спутанность появилась внезапно?")
            )
        ),
        ComplaintConcept(
            id = "pallor",
            label = "Бледность",
            aliases = listOf("бледность", "резко побледнел", "резко побледнела", "очень бледный", "очень бледная", "бледная кожа"),
            sourceFeatures = listOf("pallor"),
            clarifyingQuestions = listOf(
                q("sudden_onset", "Бледность появилась внезапно?")
            )
        ),
        ComplaintConcept(
            id = "paresthesia",
            label = "Онемение / покалывание",
            aliases = listOf(
                "онемение",
                "немеет рука",
                "немеет нога",
                "покалывание в руке",
                "покалывание в ноге",
                "мурашки в руке",
                "мурашки в ноге"
            ),
            sourceFeatures = listOf("paresthesia", "loss of sensation"),
            clarifyingQuestions = listOf(
                q("one_side", "Онемение или покалывание только с одной стороны тела?"),
                q("sudden_onset", "Онемение возникло внезапно?")
            )
        ),
        ComplaintConcept(
            id = "speech_disturbance",
            label = "Нарушение речи",
            aliases = listOf(
                "трудно говорить",
                "не могу нормально говорить",
                "речь стала невнятной",
                "невнятная речь",
                "заплетается речь",
                "сложно произносить слова"
            ),
            sourceFeatures = listOf("difficulty speaking", "slurring words"),
            clarifyingQuestions = listOf(
                q("sudden_onset", "Нарушение речи возникло внезапно?")
            )
        ),
        ComplaintConcept(
            id = "focal_weakness",
            label = "Односторонняя / очаговая слабость",
            aliases = listOf(
                "слабость одной стороны тела",
                "ослабла одна сторона тела",
                "внезапно ослабла рука",
                "внезапно ослабла нога",
                "не могу поднять одну руку",
                "одна рука стала слабой"
            ),
            sourceFeatures = listOf("focal weakness"),
            clarifyingQuestions = listOf(
                q("sudden_onset", "Слабость возникла внезапно?"),
                q("one_side", "Слабость только с одной стороны тела?")
            )
        ),
        ComplaintConcept(
            id = "abdominal_distention",
            label = "Вздутие / увеличение живота",
            aliases = listOf("вздутие живота", "живот вздулся", "живот раздуло", "живот увеличился", "сильно раздулся живот"),
            sourceFeatures = listOf("abdominal distention", "swollen abdomen", "stomach bloating"),
            clarifyingQuestions = listOf(
                q("rapid_onset", "Живот заметно увеличился за последние несколько дней?")
            )
        ),
        ComplaintConcept(
            id = "low_urine_output",
            label = "Уменьшение количества мочи",
            aliases = listOf("мало мочи", "мочи стало мало", "мочусь намного меньше", "резко уменьшилось количество мочи", "почти не мочусь"),
            sourceFeatures = listOf("low urine output"),
            clarifyingQuestions = listOf(
                q("recent", "Количество мочи заметно уменьшилось за последние сутки?")
            )
        ),
        ComplaintConcept(
            id = "seizure",
            label = "Судорожный приступ",
            aliases = listOf("судорожный приступ", "были судороги с потерей сознания", "начались судороги", "приступ судорог"),
            sourceFeatures = listOf("seizures"),
            clarifyingQuestions = listOf(
                q("first_episode", "Такой приступ произошёл впервые?")
            )
        ),
        ComplaintConcept(
            id = "fever",
            label = "Повышенная температура",
            aliases = listOf("температура", "высокая температура", "повышенная температура", "лихорадка", "температура выше 38"),
            sourceFeatures = listOf("fever"),
            clarifyingQuestions = listOf(
                q(
                    "measured_temperature",
                    "Температуру измеряли?",
                    listOf("38 °C и выше", "ниже 38 °C", "не измерял(а) / не помню")
                )
            )
        ),
        ComplaintConcept(
            id = "chills",
            label = "Озноб",
            aliases = listOf("озноб", "меня знобит", "сильно знобит", "трясет от холода", "трясёт от холода"),
            sourceFeatures = listOf("chills"),
            clarifyingQuestions = listOf(
                q("with_fever", "Озноб сопровождается повышенной температурой?")
            )
        )
    )

    val concepts: List<ComplaintConcept> = (modelConcepts + expandedConcepts).also { all ->
        require(all.map { it.id }.distinct().size == all.size) { "Duplicate complaint concept id" }
    }

    private val byId: Map<String, ComplaintConcept> = concepts.associateBy { it.id }

    val modelConceptIds: Set<String> = DiseaseCatalog.concepts.mapTo(linkedSetOf()) { it.id }

    fun concept(id: String): ComplaintConcept? = byId[id]

    fun toModelConceptIds(complaintConceptIds: Collection<String>): Set<String> =
        complaintConceptIds.mapNotNullTo(linkedSetOf()) { id -> byId[id]?.modelConceptId }

    /** Suggestions intentionally contain natural complaint phrases, not disease names. */
    fun suggestions(query: String, limit: Int = 12): List<String> {
        val normalizedQuery = normalizeForSearch(query)
        if (normalizedQuery.isBlank()) return emptyList()

        return concepts
            .flatMap { concept -> concept.aliases + concept.label }
            .distinct()
            .filter { normalizeForSearch(it).contains(normalizedQuery) }
            .sortedWith(
                compareByDescending<String> { normalizeForSearch(it).startsWith(normalizedQuery) }
                    .thenBy { it.length }
                    .thenBy { it }
            )
            .take(limit.coerceAtLeast(0))
    }

    private fun normalizeForSearch(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()
}
