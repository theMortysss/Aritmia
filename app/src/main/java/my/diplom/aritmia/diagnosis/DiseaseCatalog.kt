package my.diplom.aritmia.diagnosis

/**
 * Выход многоклассовой модели. modelScorePercent — confidence модели после softmax,
 * а не клинически откалиброванная вероятность диагноза.
 */
data class DiseaseCandidate(
    val id: String,
    val name: String,
    val modelScorePercent: Int,
    val matchedSignals: List<String>
)

data class SymptomConcept(
    val id: String,
    val label: String,
    val aliases: List<String>
)

data class DiseaseDefinition(
    val id: String,
    val name: String,
    val conceptWeights: Map<String, Double>
)

/**
 * Сердечно-сосудистая предметная область модели.
 *
 * Свободный текст сначала преобразуется в этот компактный набор симптом-концептов,
 * после чего именно их вектор подаётся на многоклассовую нейросеть. Это позволяет
 * сохранять свободную форму жалоб и при этом не привязывать модель к точной фразе.
 */
object DiseaseCatalog {

    val concepts: List<SymptomConcept> = listOf(
        SymptomConcept("palpitations", "Сердцебиение", listOf(
            "сердцебиение", "сердце колотится", "сильное сердцебиение",
            "чувствую сердцебиение", "сердце бьется сильно", "сердце сильно бьется"
        )),
        SymptomConcept("sudden_palpitations", "Внезапный приступ сердцебиения", listOf(
            "внезапная тахикардия", "внезапное сердцебиение", "приступ сердцебиения",
            "сердце резко начинает колотиться", "сердце внезапно колотится"
        )),
        SymptomConcept("irregular_rhythm", "Нерегулярный ритм", listOf(
            "пульс нерегулярный", "нерегулярный пульс", "сердце бьется неровно",
            "перебои в сердце", "чувство перебоев", "ритм неровный", "аритмия"
        )),
        SymptomConcept("skipped_beats", "Замирания / пропуски ударов", listOf(
            "сердце замирает", "пульс пропадает", "пропуски ударов", "толчки в груди",
            "экстрасистолы", "ощущение замирания сердца"
        )),
        SymptomConcept("fast_pulse", "Учащённый пульс", listOf(
            "пульс быстрый", "учащенный пульс", "учащённый пульс", "тахикардия",
            "пульс высокий", "частый пульс"
        )),
        SymptomConcept("slow_pulse", "Редкий пульс", listOf(
            "пульс редкий", "редкий пульс", "сердце бьется редко", "пульс меньше 60",
            "брадикардия", "низкий пульс"
        )),
        SymptomConcept("chest_pain", "Боль в груди", listOf(
            "боль в груди", "боль за грудиной", "боль в сердце", "кардиалгия"
        )),
        SymptomConcept("exertional_chest_pain", "Боль в груди при нагрузке", listOf(
            "боль в груди при нагрузке", "боль за грудиной при нагрузке",
            "грудь болит при ходьбе", "боль появляется при нагрузке"
        )),
        SymptomConcept("pleuritic_chest_pain", "Боль в груди при дыхании", listOf(
            "боль в груди при дыхании", "боль при вдохе", "боль усиливается при дыхании"
        )),
        SymptomConcept("positional_chest_pain", "Позиционная боль в груди", listOf(
            "боль усиливается лежа", "боль уменьшается сидя", "легче сидя наклонившись",
            "боль зависит от положения тела"
        )),
        SymptomConcept("pain_radiation", "Иррадиация боли в руку / челюсть", listOf(
            "боль отдает в руку", "боль отдаёт в руку", "боль отдает в левую руку",
            "боль отдаёт в левую руку", "боль отдает в челюсть", "боль отдаёт в челюсть"
        )),
        SymptomConcept("chest_pressure", "Сдавление / давление в груди", listOf(
            "давление в груди", "грудь давит", "сдавление в груди", "чувство сдавления",
            "тяжесть в груди", "дискомфорт в груди"
        )),
        SymptomConcept("dyspnea", "Одышка / нехватка воздуха", listOf(
            "одышка", "тяжело дышать", "не хватает воздуха", "чувство нехватки воздуха",
            "ощущение удушья", "задыхаюсь"
        )),
        SymptomConcept("exertional_dyspnea", "Одышка при нагрузке", listOf(
            "одышка при нагрузке", "одышка при ходьбе", "задыхаюсь при ходьбе",
            "не хватает воздуха при нагрузке"
        )),
        SymptomConcept("orthopnea", "Одышка лёжа", listOf(
            "одышка лежа", "одышка лёжа", "тяжело дышать лежа", "тяжело дышать лёжа",
            "задыхаюсь лежа", "задыхаюсь лёжа"
        )),
        SymptomConcept("nocturnal_dyspnea", "Ночная одышка / удушье", listOf(
            "одышка ночью", "просыпаюсь от удушья", "просыпаюсь от одышки",
            "ночью не хватает воздуха", "пароксизмальная ночная одышка"
        )),
        SymptomConcept("edema", "Отёки ног", listOf(
            "ноги отекают", "отёки на ногах", "отеки на ногах", "отеки стоп", "отёки стоп",
            "отеки лодыжек", "отёки лодыжек"
        )),
        SymptomConcept("syncope", "Обморок", listOf(
            "обморок", "обмороки", "теряю сознание", "потеря сознания", "синкопе"
        )),
        SymptomConcept("exertional_syncope", "Обморок при нагрузке", listOf(
            "обморок при нагрузке", "обмороки при нагрузке", "теряю сознание при нагрузке",
            "обморок во время ходьбы"
        )),
        SymptomConcept("dizziness", "Головокружение", listOf(
            "голова кружится", "головокружение", "кружится голова", "предобморочное состояние"
        )),
        SymptomConcept("weakness", "Слабость", listOf(
            "слабость", "сильная слабость", "выраженная слабость", "нет сил"
        )),
        SymptomConcept("fatigue", "Утомляемость", listOf(
            "усталость", "утомляемость", "быстро устаю", "повышенная утомляемость"
        )),
        SymptomConcept("cold_sweat", "Холодный пот", listOf(
            "холодный пот", "липкий пот", "внезапная потливость", "сильная потливость"
        )),
        SymptomConcept("nausea", "Тошнота", listOf(
            "тошнота", "тошнит", "подташнивает"
        )),
        SymptomConcept("high_bp", "Повышенное давление", listOf(
            "давление высокое", "высокое давление", "давление выше 140",
            "повышенное артериальное давление", "гипертония", "гипертензия"
        )),
        SymptomConcept("headache", "Головная боль", listOf(
            "головная боль", "болит голова", "сильно болит голова"
        )),
        SymptomConcept("murmur", "Шум в сердце", listOf(
            "шум в сердце", "сердечный шум", "врач слышал шум в сердце"
        )),
        SymptomConcept("fever", "Повышенная температура", listOf(
            "температура", "лихорадка", "повышенная температура", "жар"
        )),
        SymptomConcept("anxiety", "Тревога / страх", listOf(
            "тревога", "чувство страха", "паника", "страх", "тревожность"
        ))
    )

    val definitions: List<DiseaseDefinition> = listOf(
        DiseaseDefinition("atrial_fibrillation", "Фибрилляция предсердий", mapOf(
            "irregular_rhythm" to 1.0, "palpitations" to 0.75, "dyspnea" to 0.55,
            "dizziness" to 0.45, "weakness" to 0.35, "fatigue" to 0.3
        )),
        DiseaseDefinition("supraventricular_tachycardia", "Наджелудочковая тахикардия", mapOf(
            "sudden_palpitations" to 1.0, "fast_pulse" to 0.95, "palpitations" to 0.9,
            "dizziness" to 0.45, "dyspnea" to 0.35, "anxiety" to 0.25
        )),
        DiseaseDefinition("extrasystole", "Экстрасистолия", mapOf(
            "skipped_beats" to 1.0, "irregular_rhythm" to 0.75, "palpitations" to 0.55,
            "anxiety" to 0.2
        )),
        DiseaseDefinition("sinus_bradycardia", "Брадикардия", mapOf(
            "slow_pulse" to 1.0, "weakness" to 0.65, "dizziness" to 0.65,
            "syncope" to 0.55, "fatigue" to 0.4
        )),
        DiseaseDefinition("stable_angina", "Ишемическая болезнь сердца / стенокардия", mapOf(
            "exertional_chest_pain" to 1.0, "chest_pressure" to 0.85, "chest_pain" to 0.75,
            "pain_radiation" to 0.7, "exertional_dyspnea" to 0.55, "dyspnea" to 0.35
        )),
        DiseaseDefinition("acute_coronary_syndrome", "Острый коронарный синдром / инфаркт миокарда", mapOf(
            "chest_pain" to 1.0, "chest_pressure" to 0.9, "pain_radiation" to 0.95,
            "cold_sweat" to 0.85, "nausea" to 0.65, "dyspnea" to 0.65,
            "weakness" to 0.55
        )),
        DiseaseDefinition("heart_failure", "Хроническая сердечная недостаточность", mapOf(
            "orthopnea" to 1.0, "nocturnal_dyspnea" to 0.95, "edema" to 0.9,
            "exertional_dyspnea" to 0.85, "dyspnea" to 0.65, "fatigue" to 0.55,
            "weakness" to 0.4
        )),
        DiseaseDefinition("arterial_hypertension", "Артериальная гипертензия", mapOf(
            "high_bp" to 1.0, "headache" to 0.5, "dizziness" to 0.4,
            "palpitations" to 0.25
        )),
        DiseaseDefinition("aortic_stenosis", "Аортальный стеноз", mapOf(
            "exertional_syncope" to 1.0, "exertional_chest_pain" to 0.9,
            "exertional_dyspnea" to 0.9, "murmur" to 0.8, "dizziness" to 0.45,
            "syncope" to 0.45
        )),
        DiseaseDefinition("pericarditis", "Перикардит", mapOf(
            "positional_chest_pain" to 1.0, "pleuritic_chest_pain" to 0.95,
            "chest_pain" to 0.7, "fever" to 0.45, "weakness" to 0.25
        )),
        DiseaseDefinition("dilated_cardiomyopathy", "Дилатационная кардиомиопатия", mapOf(
            "exertional_dyspnea" to 0.9, "orthopnea" to 0.85, "edema" to 0.8,
            "fatigue" to 0.65, "weakness" to 0.5, "palpitations" to 0.45,
            "irregular_rhythm" to 0.4
        )),
        DiseaseDefinition("sinus_tachycardia", "Синусовая тахикардия", mapOf(
            "fast_pulse" to 1.0, "palpitations" to 0.85, "anxiety" to 0.45,
            "weakness" to 0.3, "dizziness" to 0.25
        ))
    )

    private val diseaseById = definitions.associateBy { it.id }
    private val conceptById = concepts.associateBy { it.id }

    fun disease(id: String): DiseaseDefinition? = diseaseById[id]
    fun concept(id: String): SymptomConcept? = conceptById[id]

    fun explain(diseaseId: String, matchedConceptIds: Collection<String>): List<String> {
        val disease = diseaseById[diseaseId] ?: return emptyList()
        return matchedConceptIds
            .filter { it in disease.conceptWeights }
            .sortedByDescending { disease.conceptWeights[it] ?: 0.0 }
            .mapNotNull { conceptById[it]?.label }
    }
}
