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
) {
    val matchPercent: Int get() = modelScorePercent
}

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
 * Единая symptom ontology для Android и offline ML pipeline.
 * Порядок concepts является частью формата pretrained disease_model.json.
 */
object DiseaseCatalog {

    val concepts: List<SymptomConcept> = listOf(
        SymptomConcept("vomiting", "Рвота", listOf("рвота", "меня рвет", "вырвало", "тошнит и рвет")),
        SymptomConcept("cough", "Кашель", listOf("кашель", "кашляю", "постоянно кашляю")),
        SymptomConcept("fatigue", "Утомляемость", listOf("усталость", "утомляемость", "быстро устаю", "повышенная утомляемость")),
        SymptomConcept("headache", "Головная боль", listOf("головная боль", "болит голова", "сильно болит голова")),
        SymptomConcept("dizziness", "Головокружение", listOf("голова кружится", "головокружение", "кружится голова", "предобморочное состояние", "теряю равновесие")),
        SymptomConcept("sweating", "Потливость", listOf("потливость", "сильно потею", "холодный пот", "липкий пот", "внезапно бросает в пот")),
        SymptomConcept("palpitations", "Сердцебиение", listOf("сердцебиение", "сердце колотится", "сердце сильно бьется", "чувствую сердцебиение", "сердце стучит")),
        SymptomConcept("dyspnea", "Одышка / нехватка воздуха", listOf("одышка", "тяжело дышать", "не хватает воздуха", "задыхаюсь", "не могу нормально вдохнуть", "затрудненное дыхание")),
        SymptomConcept("chest_pain", "Боль в груди", listOf("боль в груди", "болит грудь", "боль за грудиной", "боль в сердце", "резкая боль в груди")),
        SymptomConcept("chest_tightness", "Стеснение в груди", listOf("стеснение в груди", "грудь сжимает", "сжатие в груди", "тесно в груди")),
        SymptomConcept("irregular_heartbeat", "Нерегулярный ритм", listOf("пульс нерегулярный", "нерегулярный пульс", "сердце бьется неровно", "перебои в сердце", "ритм неровный", "аритмия", "сердце сбивается")),
        SymptomConcept("syncope", "Обморок", listOf("обморок", "обмороки", "теряю сознание", "потеря сознания", "упал в обморок")),
        SymptomConcept("abdominal_pain", "Боль в животе", listOf("боль в животе", "болит живот", "боль в желудке", "болит желудок", "боль в верхней части живота")),
        SymptomConcept("arm_pain", "Боль в руке", listOf("боль в руке", "болит рука", "боль в левой руке", "отдает в руку", "отдаёт в руку")),
        SymptomConcept("back_pain", "Боль в спине", listOf("боль в спине", "болит спина", "боль между лопатками", "боль в пояснице")),
        SymptomConcept("burning_abdominal_pain", "Жжение в животе", listOf("жжение в животе", "жжет в животе", "жжение в желудке")),
        SymptomConcept("edema", "Отёки", listOf("ноги отекают", "отеки ног", "отёки ног", "отеки стоп", "отёки стоп", "отеки лодыжек", "отёки лодыжек", "задержка жидкости")),
        SymptomConcept("weight_gain", "Набор веса", listOf("набираю вес", "быстро набрал вес", "резкий набор веса", "вес увеличился")),
        SymptomConcept("weakness", "Слабость", listOf("слабость", "сильная слабость", "нет сил", "мышечная слабость", "чувствую себя слабым")),
        SymptomConcept("slow_heart_rate", "Редкий пульс", listOf("пульс редкий", "редкий пульс", "сердце бьется редко", "пульс меньше 60", "брадикардия", "низкий пульс", "пульс замедлился")),
        SymptomConcept("fast_heart_rate", "Учащённый пульс", listOf("пульс быстрый", "частый пульс", "учащенный пульс", "учащённый пульс", "тахикардия", "сердце бьется очень быстро", "пульс ускорился")),
        SymptomConcept("hemoptysis", "Кровохарканье", listOf("кашель с кровью", "кровь при кашле", "кровь в мокроте", "отхаркиваю кровь", "кровохарканье")),
        SymptomConcept("apnea", "Остановки дыхания во сне", listOf("останавливается дыхание во сне", "перестаю дышать во сне", "апноэ", "паузы дыхания во сне")),
        SymptomConcept("burning_chest_pain", "Жжение в груди", listOf("жжение в груди", "жжет в груди", "печет в груди", "жгучая боль в груди")),
        SymptomConcept("pleuritic_pain", "Боль при дыхании", listOf("боль при вдохе", "больно дышать", "боль усиливается при дыхании", "боль в груди при дыхании")),
        SymptomConcept("chest_pressure", "Давление / тяжесть в груди", listOf("давление в груди", "грудь давит", "тяжесть в груди", "сдавление в груди", "давящая боль в груди")),
        SymptomConcept("high_bp", "Повышенное давление", listOf("высокое давление", "давление высокое", "повышенное давление", "гипертония", "гипертензия", "давление выше 140")),
        SymptomConcept("nausea", "Тошнота", listOf("тошнота", "тошнит", "подташнивает")),
        SymptomConcept("leg_pain", "Боль в ногах", listOf("боль в ногах", "болят ноги", "боль в ноге")),
        SymptomConcept("leg_cramps", "Судороги ног", listOf("судороги в ногах", "сводит ноги", "спазмы в ногах")),
        SymptomConcept("jaw_pain", "Боль в челюсти", listOf("боль в челюсти", "болит челюсть", "отдает в челюсть", "отдаёт в челюсть")),
        SymptomConcept("neck_pain", "Боль в шее", listOf("боль в шее", "болит шея")),
        SymptomConcept("shoulder_pain", "Боль в плече", listOf("боль в плече", "болит плечо", "отдает в плечо", "отдаёт в плечо")),
        SymptomConcept("tachypnea", "Учащённое дыхание", listOf("часто дышу", "дыхание учащенное", "дыхание учащённое", "быстро дышу", "учащенное дыхание")),
        SymptomConcept("nocturia", "Частое мочеиспускание ночью", listOf("часто мочусь ночью", "ночью часто хожу в туалет", "частое мочеиспускание ночью", "ночная полиурия")),
        SymptomConcept("chest_congestion", "Заложенность / тяжесть в груди", listOf("заложенность в груди", "грудь заложена", "тяжело в груди")),
        SymptomConcept("abnormal_breathing_sounds", "Свистящее дыхание", listOf("свист при дыхании", "свистящее дыхание", "хрипы", "слышу хрипы при дыхании")),
        SymptomConcept("heartburn", "Изжога", listOf("изжога", "кислота поднимается", "жжение за грудиной после еды")),
        SymptomConcept("sleep_issues", "Нарушение сна", listOf("плохо сплю", "не могу уснуть", "бессонница", "нарушение сна", "часто просыпаюсь ночью")),
        SymptomConcept("arm_swelling", "Отёк руки", listOf("рука отекла", "отек руки", "отёк руки", "рука опухла")),
        SymptomConcept("leg_weakness", "Слабость в ногах", listOf("слабость в ногах", "ноги слабые", "ноги подкашиваются")),
        SymptomConcept("arm_weakness", "Слабость в руках", listOf("слабость в руках", "руки слабые", "слабость в руке")),
        SymptomConcept("ankle_pain", "Боль в лодыжке", listOf("боль в лодыжке", "болит лодыжка", "боль в голеностопе")),
        SymptomConcept("rib_pain", "Боль в рёбрах", listOf("боль в ребрах", "боль в рёбрах", "болят ребра", "болят рёбра")),
        SymptomConcept("painful_walking", "Боль при ходьбе", listOf("больно ходить", "боль при ходьбе", "при ходьбе болят ноги")),
        SymptomConcept("neck_stiffness", "Скованность шеи", listOf("шея затекла", "скованность шеи", "тугая шея", "не могу нормально повернуть шею")),
        SymptomConcept("phlegm", "Мокрота", listOf("мокрота", "кашель с мокротой", "отхаркивается мокрота"))
    )

    val definitions: List<DiseaseDefinition> = listOf(
        DiseaseDefinition("atrial_fibrillation", "Фибрилляция / трепетание предсердий", mapOf("irregular_heartbeat" to 1.0, "palpitations" to 0.9, "dyspnea" to 0.55, "dizziness" to 0.45, "fatigue" to 0.35)),
        DiseaseDefinition("supraventricular_tachycardia", "Наджелудочковая тахикардия", mapOf("fast_heart_rate" to 1.0, "palpitations" to 0.95, "dizziness" to 0.45, "dyspnea" to 0.35, "chest_pain" to 0.3)),
        DiseaseDefinition("ventricular_tachycardia", "Желудочковая тахикардия", mapOf("fast_heart_rate" to 1.0, "palpitations" to 0.8, "syncope" to 0.8, "dizziness" to 0.65, "chest_pain" to 0.45, "dyspnea" to 0.45)),
        DiseaseDefinition("sinus_bradycardia", "Синусовая брадикардия", mapOf("slow_heart_rate" to 1.0, "dizziness" to 0.65, "weakness" to 0.6, "fatigue" to 0.55, "syncope" to 0.45)),
        DiseaseDefinition("heart_block", "Нарушение AV-проводимости / блокада сердца", mapOf("slow_heart_rate" to 0.9, "syncope" to 0.9, "dizziness" to 0.75, "fatigue" to 0.55, "weakness" to 0.5, "dyspnea" to 0.35)),
        DiseaseDefinition("stable_angina", "Ишемическая болезнь сердца / стенокардия", mapOf("chest_pain" to 1.0, "chest_pressure" to 0.9, "chest_tightness" to 0.85, "arm_pain" to 0.65, "jaw_pain" to 0.65, "shoulder_pain" to 0.5, "dyspnea" to 0.45)),
        DiseaseDefinition("acute_coronary_syndrome", "Острый коронарный синдром / инфаркт миокарда", mapOf("chest_pain" to 1.0, "chest_pressure" to 0.95, "sweating" to 0.75, "nausea" to 0.65, "vomiting" to 0.5, "arm_pain" to 0.7, "jaw_pain" to 0.65, "dyspnea" to 0.6)),
        DiseaseDefinition("heart_failure", "Сердечная недостаточность", mapOf("dyspnea" to 1.0, "edema" to 0.95, "weight_gain" to 0.75, "fatigue" to 0.65, "weakness" to 0.45, "nocturia" to 0.4, "cough" to 0.35)),
        DiseaseDefinition("arterial_hypertension", "Артериальная / гипертензивная болезнь", mapOf("high_bp" to 1.0, "headache" to 0.55, "dizziness" to 0.45, "palpitations" to 0.3, "dyspnea" to 0.25)),
        DiseaseDefinition("pericarditis", "Перикардит", mapOf("pleuritic_pain" to 1.0, "chest_pain" to 0.85, "dyspnea" to 0.5, "palpitations" to 0.35, "cough" to 0.25)),
        DiseaseDefinition("cardiomyopathy", "Кардиомиопатия", mapOf("dyspnea" to 0.9, "fatigue" to 0.75, "edema" to 0.7, "palpitations" to 0.6, "chest_pain" to 0.4, "syncope" to 0.35)),
        DiseaseDefinition("aortic_valve_disease", "Заболевание аортального клапана", mapOf("dyspnea" to 0.85, "chest_pain" to 0.75, "syncope" to 0.7, "fatigue" to 0.45, "palpitations" to 0.35)),
        DiseaseDefinition("pulmonary_hypertension", "Лёгочная гипертензия", mapOf("dyspnea" to 1.0, "fatigue" to 0.65, "dizziness" to 0.55, "syncope" to 0.45, "edema" to 0.5, "chest_pain" to 0.4, "hemoptysis" to 0.35)),
        DiseaseDefinition("aortic_aneurysm", "Аневризма аорты", mapOf("chest_pain" to 0.9, "back_pain" to 0.9, "abdominal_pain" to 0.8, "dyspnea" to 0.35, "cough" to 0.25, "syncope" to 0.25))
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
