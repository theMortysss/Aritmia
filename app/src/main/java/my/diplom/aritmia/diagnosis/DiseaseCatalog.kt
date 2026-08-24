package my.diplom.aritmia.diagnosis

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Результат сопоставления введённых жалоб с профилем сердечно-сосудистого состояния.
 * matchPercent — не медицинская вероятность, а нормированная оценка совпадения симптомов.
 */
data class DiseaseCandidate(
    val id: String,
    val name: String,
    val matchPercent: Int,
    val matchedSignals: List<String>
)

private data class DiseaseSignal(
    val phrase: String,
    val weight: Double
)

private data class DiseaseProfile(
    val id: String,
    val name: String,
    val signals: List<DiseaseSignal>
)

/**
 * Интерпретируемый слой предварительного ранжирования заболеваний.
 *
 * Он намеренно отделён от общей NN-оценки аритмических признаков: текущая нейросеть
 * обучается на синтетических примерах и имеет один выход, поэтому её значение нельзя
 * честно трактовать как вероятность конкретного диагноза.
 *
 * Профили ниже предназначены для учебного скринингового прототипа и требуют
 * клинической валидации перед любым реальным медицинским применением.
 */
object DiseaseCatalog {

    private fun s(phrase: String, weight: Double) = DiseaseSignal(phrase, weight)

    private val profiles = listOf(
        DiseaseProfile(
            id = "atrial_fibrillation",
            name = "Фибрилляция предсердий",
            signals = listOf(
                s("пульс нерегулярный", 1.0),
                s("сердце бьется неровно", 1.0),
                s("перебои в сердце", 0.95),
                s("чувство перебоев", 0.9),
                s("нерегулярный пульс", 1.0),
                s("неровный ритм", 0.9),
                s("одышка", 0.45),
                s("голова кружится", 0.4),
                s("слабость", 0.35)
            )
        ),
        DiseaseProfile(
            id = "supraventricular_tachycardia",
            name = "Наджелудочковая тахикардия",
            signals = listOf(
                s("внезапная тахикардия", 1.0),
                s("сердце колотится", 0.9),
                s("пульс быстрый", 0.9),
                s("сильное сердцебиение", 0.85),
                s("учащённый пульс", 0.85),
                s("сердцебиение в покое", 0.75),
                s("голова кружится", 0.35),
                s("чувство страха", 0.25)
            )
        ),
        DiseaseProfile(
            id = "extrasystole",
            name = "Экстрасистолия",
            signals = listOf(
                s("сердце замирает", 1.0),
                s("пульс пропадает", 0.95),
                s("чувство перебоев", 0.9),
                s("перебои в сердце", 0.85),
                s("толчки в груди", 0.95),
                s("пропуски ударов", 0.95),
                s("ощущение замирания сердца", 1.0)
            )
        ),
        DiseaseProfile(
            id = "sinus_bradycardia",
            name = "Брадикардия",
            signals = listOf(
                s("пульс редкий", 1.0),
                s("сердце бьется редко", 1.0),
                s("пульс меньше 60", 0.95),
                s("брадикардия", 1.0),
                s("слабость", 0.55),
                s("голова кружится", 0.55),
                s("обмороки", 0.65)
            )
        ),
        DiseaseProfile(
            id = "stable_angina",
            name = "Ишемическая болезнь сердца / стенокардия",
            signals = listOf(
                s("боль за грудиной", 1.0),
                s("боль в груди при нагрузке", 1.0),
                s("сдавление при нагрузке", 0.95),
                s("дискомфорт при нагрузке", 0.85),
                s("тяжесть при нагрузке", 0.85),
                s("давление в груди", 0.75),
                s("одышка при ходьбе", 0.55),
                s("боль отдает в руку", 0.75),
                s("боль отдает в челюсть", 0.75)
            )
        ),
        DiseaseProfile(
            id = "acute_coronary_syndrome",
            name = "Острый коронарный синдром / инфаркт миокарда",
            signals = listOf(
                s("боль за грудиной", 1.0),
                s("сильная боль в груди", 1.0),
                s("боль отдает в руку", 1.0),
                s("боль отдает в челюсть", 1.0),
                s("холодный пот", 0.85),
                s("тошнота", 0.65),
                s("сильная слабость", 0.65),
                s("давление в груди", 0.75),
                s("одышка", 0.6)
            )
        ),
        DiseaseProfile(
            id = "heart_failure",
            name = "Хроническая сердечная недостаточность",
            signals = listOf(
                s("одышка лежа", 1.0),
                s("просыпаюсь от удушья", 1.0),
                s("одышка ночью", 0.95),
                s("одышка при ходьбе", 0.85),
                s("ноги отекают", 0.9),
                s("отёки на ногах", 0.9),
                s("отеки стоп", 0.85),
                s("сильная слабость", 0.55),
                s("усталость", 0.5)
            )
        ),
        DiseaseProfile(
            id = "arterial_hypertension",
            name = "Артериальная гипертензия",
            signals = listOf(
                s("давление высокое", 1.0),
                s("давление выше 140", 1.0),
                s("повышенное артериальное давление", 1.0),
                s("головная боль", 0.55),
                s("голова кружится", 0.45),
                s("чувство тяжести", 0.3),
                s("сердцебиение", 0.3)
            )
        ),
        DiseaseProfile(
            id = "aortic_stenosis",
            name = "Аортальный стеноз",
            signals = listOf(
                s("обморок при нагрузке", 1.0),
                s("обмороки при нагрузке", 1.0),
                s("боль в груди при нагрузке", 0.9),
                s("одышка при нагрузке", 0.9),
                s("одышка при ходьбе", 0.75),
                s("голова кружится при нагрузке", 0.85),
                s("шум в сердце", 0.75)
            )
        ),
        DiseaseProfile(
            id = "pericarditis",
            name = "Перикардит",
            signals = listOf(
                s("боль усиливается лежа", 1.0),
                s("боль уменьшается сидя", 1.0),
                s("боль в груди при дыхании", 0.9),
                s("боль при дыхании", 0.85),
                s("жжение в груди", 0.4),
                s("температура", 0.4),
                s("слабость", 0.3)
            )
        ),
        DiseaseProfile(
            id = "dilated_cardiomyopathy",
            name = "Дилатационная кардиомиопатия",
            signals = listOf(
                s("одышка при ходьбе", 0.85),
                s("одышка лежа", 0.9),
                s("ноги отекают", 0.8),
                s("отеки стоп", 0.75),
                s("сильная слабость", 0.6),
                s("усталость", 0.5),
                s("перебои в сердце", 0.55),
                s("сердцебиение", 0.5)
            )
        ),
        DiseaseProfile(
            id = "sinus_tachycardia",
            name = "Синусовая тахикардия",
            signals = listOf(
                s("пульс быстрый", 1.0),
                s("учащённый пульс", 1.0),
                s("сердце бьется сильно", 0.8),
                s("сильное сердцебиение", 0.8),
                s("сердцебиение при стрессе", 0.75),
                s("тревога", 0.35),
                s("потливость", 0.3)
            )
        )
    )

    fun rank(
        symptoms: List<String>,
        medicalTerms: List<String> = emptyList(),
        limit: Int = 5
    ): List<DiseaseCandidate> {
        if (limit <= 0) return emptyList()

        val inputs = (symptoms + medicalTerms)
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinct()

        if (inputs.isEmpty()) return emptyList()

        return profiles.mapNotNull { profile ->
            val matched = profile.signals.filter { signal ->
                val normalizedSignal = normalize(signal.phrase)
                inputs.any { input -> matches(input, normalizedSignal) }
            }

            if (matched.isEmpty()) return@mapNotNull null

            // Нормируем относительно пяти наиболее информативных признаков профиля,
            // чтобы один общий симптом не давал искусственно высокий процент.
            val referenceWeight = profile.signals
                .map { it.weight }
                .sortedDescending()
                .take(5)
                .sum()
                .coerceAtLeast(1.0)

            val matchedWeight = matched.sumOf { it.weight }
            val coverage = (matchedWeight / referenceWeight).coerceIn(0.0, 1.0)
            val breadth = (matched.size.toDouble() / min(4, profile.signals.size))
                .coerceIn(0.0, 1.0)
            val score = ((coverage * 0.8 + breadth * 0.2) * 100.0)
                .roundToInt()
                .coerceIn(1, 100)

            DiseaseCandidate(
                id = profile.id,
                name = profile.name,
                matchPercent = score,
                matchedSignals = matched.map { it.phrase }.distinct()
            )
        }
            .sortedWith(
                compareByDescending<DiseaseCandidate> { it.matchPercent }
                    .thenByDescending { it.matchedSignals.size }
                    .thenBy { it.name }
            )
            .take(limit)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9%+\\s-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun matches(input: String, signal: String): Boolean {
        if (input == signal) return true
        if (input.contains(signal)) return true
        // Позволяет распознавать короткую пользовательскую формулировку внутри
        // более подробного сигнала, но отсекает слишком общие слова вроде «боль».
        return input.length >= 6 && signal.contains(input)
    }
}
