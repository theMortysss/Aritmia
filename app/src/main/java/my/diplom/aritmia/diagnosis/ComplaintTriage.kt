package my.diplom.aritmia.diagnosis

enum class ComplaintTriageLevel {
    NONE,
    MEDICAL_REVIEW,
    EMERGENCY
}

data class ComplaintTriageFlag(
    val id: String,
    val level: ComplaintTriageLevel,
    val title: String,
    val message: String,
    val matchedConceptIds: Set<String>
)

data class ComplaintTriageAssessment(
    val level: ComplaintTriageLevel = ComplaintTriageLevel.NONE,
    val flags: List<ComplaintTriageFlag> = emptyList()
) {
    val requiresImmediateAction: Boolean get() = level == ComplaintTriageLevel.EMERGENCY
}

/**
 * Conservative, deterministic urgency layer independent from disease ranking.
 *
 * Sources used to define the rules:
 * - 2021 AHA/ACC Chest Pain Guideline: acute chest pain/equivalent symptoms require
 *   immediate medical evaluation; equivalents include pressure/tightness, arm/jaw/back
 *   discomfort, shortness of breath and fatigue.
 * - American Stroke Association B.E.F.A.S.T.: sudden vision change, unilateral weakness,
 *   speech difficulty or related sudden neurologic deficits require emergency action.
 * - American Heart Association blood-pressure guidance: >180/120 plus chest pain,
 *   dyspnea, back pain, numbness/weakness, vision or speech change is a hypertensive
 *   emergency; <90/60 alone is not automatically dangerous, but symptomatic hypotension
 *   warrants medical assessment.
 * - 2018 ESC Syncope Guideline: exertional/supine syncope or syncope immediately after
 *   palpitations are high-risk features requiring prompt clinical evaluation.
 * - 2022 ACC/AHA Aortic Disease Guideline: abrupt-onset severe chest, back or abdominal
 *   pain is a high-risk pain feature for acute aortic syndrome and needs urgent evaluation.
 * - American Heart Association pulmonary-embolism guidance: sudden unexplained dyspnea,
 *   pleuritic chest pain, hemoptysis and syncope are warning features requiring prompt care.
 *
 * This object does not diagnose disease and never modifies the pretrained MLP input.
 */
object ComplaintTriage {

    private val strokeLikeConcepts = setOf(
        "speech_disturbance",
        "focal_weakness",
        "visual_disturbance",
        "paresthesia",
        "confusion"
    )

    private val chestConcepts = setOf("chest_pain", "chest_pressure", "chest_tightness")
    private val acuteAorticPainConcepts = setOf("back_pain", "abdominal_pain")
    private val pulmonaryEmbolismCompanionConcepts = setOf("hemoptysis", "pleuritic_pain", "syncope")

    private val hypertensiveEmergencySymptoms = setOf(
        "chest_pain",
        "chest_pressure",
        "chest_tightness",
        "dyspnea",
        "back_pain",
        "paresthesia",
        "focal_weakness",
        "visual_disturbance",
        "speech_disturbance",
        "confusion"
    )

    private val hypotensionSymptoms = setOf(
        "confusion",
        "dizziness",
        "nausea",
        "syncope",
        "fatigue",
        "visual_disturbance",
        "palpitations",
        "weakness"
    )

    fun assess(
        complaints: List<String>,
        clarificationAnswers: Map<String, String> = emptyMap()
    ): ComplaintTriageAssessment {
        val extraction = FreeTextSymptomExtractor.extract(complaints)
        val concepts = extraction.conceptIds
        if (concepts.isEmpty()) return ComplaintTriageAssessment()

        val flags = mutableListOf<ComplaintTriageFlag>()

        addStrokeFlags(complaints, concepts, clarificationAnswers, flags)
        addChestFlags(complaints, concepts, clarificationAnswers, flags)
        addAorticFlags(complaints, concepts, clarificationAnswers, flags)
        addDyspneaFlags(complaints, concepts, clarificationAnswers, flags)
        addHemoptysisFlags(concepts, flags)
        addBloodPressureFlags(concepts, clarificationAnswers, flags)
        addSyncopeFlags(complaints, concepts, clarificationAnswers, flags)

        val deduplicated = flags.distinctBy { it.id }
        val level = when {
            deduplicated.any { it.level == ComplaintTriageLevel.EMERGENCY } -> ComplaintTriageLevel.EMERGENCY
            deduplicated.any { it.level == ComplaintTriageLevel.MEDICAL_REVIEW } -> ComplaintTriageLevel.MEDICAL_REVIEW
            else -> ComplaintTriageLevel.NONE
        }
        return ComplaintTriageAssessment(level = level, flags = deduplicated)
    }

    private fun addStrokeFlags(
        complaints: List<String>,
        concepts: Set<String>,
        answers: Map<String, String>,
        flags: MutableList<ComplaintTriageFlag>
    ) {
        val present = concepts.intersect(strokeLikeConcepts)
        if (present.isEmpty()) return

        val suddenFromAnswer = present.any { conceptId ->
            answerIsYes(answers, conceptId, "sudden_onset")
        }
        val suddenFromText = complaints.any { complaint ->
            val local = FreeTextSymptomExtractor.extract(listOf(complaint)).conceptIds
            local.any { it in strokeLikeConcepts } && hasSuddenMarker(complaint)
        }

        if (suddenFromAnswer || suddenFromText) {
            flags += ComplaintTriageFlag(
                id = "sudden_neurologic_deficit",
                level = ComplaintTriageLevel.EMERGENCY,
                title = "Возможны признаки острого неврологического состояния",
                message = "Внезапное нарушение речи или зрения, односторонняя слабость/онемение или спутанность требуют экстренной медицинской оценки. Не ждите появления дополнительных симптомов.",
                matchedConceptIds = present
            )
        } else {
            flags += ComplaintTriageFlag(
                id = "neurologic_symptom_review",
                level = ComplaintTriageLevel.MEDICAL_REVIEW,
                title = "Неврологический симптом требует внимания",
                message = "Если нарушение речи, зрения, слабость, онемение или спутанность появились внезапно, нужна экстренная помощь. Даже без явного внезапного начала этот симптом стоит обсудить с медицинским специалистом.",
                matchedConceptIds = present
            )
        }
    }

    private fun addChestFlags(
        complaints: List<String>,
        concepts: Set<String>,
        answers: Map<String, String>,
        flags: MutableList<ComplaintTriageFlag>
    ) {
        val present = concepts.intersect(chestConcepts)
        if (present.isEmpty()) return

        val acuteFromAnswers = answerIsYes(answers, "chest_pain", "sudden_onset") ||
            answerIsYes(answers, "chest_pain", "present_now") ||
            answerIsYes(answers, "chest_pressure", "present_now")
        val acuteFromText = complaints.any { complaint ->
            val local = FreeTextSymptomExtractor.extract(listOf(complaint)).conceptIds
            local.any { it in chestConcepts } && hasAcuteChestMarker(complaint)
        }

        if (acuteFromAnswers || acuteFromText) {
            flags += ComplaintTriageFlag(
                id = "acute_chest_symptoms",
                level = ComplaintTriageLevel.EMERGENCY,
                title = "Острый дискомфорт в груди требует срочной оценки",
                message = "Острая боль, давление или сжатие в груди могут иметь опасные причины. Если симптом происходит сейчас или возник внезапно, обратитесь за экстренной медицинской помощью; не откладывайте её ради заполнения приложения.",
                matchedConceptIds = present
            )
        } else {
            flags += ComplaintTriageFlag(
                id = "chest_symptom_review",
                level = ComplaintTriageLevel.MEDICAL_REVIEW,
                title = "Дискомфорт в груди требует медицинской оценки",
                message = "По одной жалобе приложение не определяет причину боли или давления в груди. Если симптом усиливается, стал острым или сохраняется сейчас, нужна срочная медицинская оценка.",
                matchedConceptIds = present
            )
        }
    }

    private fun addAorticFlags(
        complaints: List<String>,
        concepts: Set<String>,
        answers: Map<String, String>,
        flags: MutableList<ComplaintTriageFlag>
    ) {
        val present = concepts.intersect(acuteAorticPainConcepts)
        if (present.isEmpty()) return

        val suddenSevereFromAnswer = present.any { conceptId ->
            answerIsYes(answers, conceptId, "sudden_severe")
        }
        val suddenSevereFromText = complaints.any { complaint ->
            val local = FreeTextSymptomExtractor.extract(listOf(complaint)).conceptIds
            local.any { it in acuteAorticPainConcepts } && hasSuddenSeverePainMarker(complaint)
        }

        if (suddenSevereFromAnswer || suddenSevereFromText) {
            flags += ComplaintTriageFlag(
                id = "acute_aortic_pain_pattern",
                level = ComplaintTriageLevel.EMERGENCY,
                title = "Внезапная сильная боль в спине или животе требует срочной оценки",
                message = "Внезапная боль в спине или животе, которая сразу была сильной, относится к признакам, при которых нужно срочно исключить острое заболевание аорты и другие опасные причины. Нужна экстренная медицинская оценка; приложение не определяет причину боли.",
                matchedConceptIds = present
            )
        }
    }

    private fun addDyspneaFlags(
        complaints: List<String>,
        concepts: Set<String>,
        answers: Map<String, String>,
        flags: MutableList<ComplaintTriageFlag>
    ) {
        if ("dyspnea" !in concepts) return

        val suddenFromAnswer = answerIsYes(answers, "dyspnea", "sudden_onset")
        val suddenFromText = complaints.any { complaint ->
            val local = FreeTextSymptomExtractor.extract(listOf(complaint)).conceptIds
            "dyspnea" in local && hasSuddenMarker(complaint)
        }
        val sudden = suddenFromAnswer || suddenFromText
        val atRest = answerIsYes(answers, "dyspnea", "at_rest")
        val peCompanions = concepts.intersect(pulmonaryEmbolismCompanionConcepts)

        if (sudden && peCompanions.isNotEmpty()) {
            flags += ComplaintTriageFlag(
                id = "pulmonary_embolism_warning_pattern",
                level = ComplaintTriageLevel.EMERGENCY,
                title = "Внезапная одышка с дополнительным опасным признаком",
                message = "Внезапная нехватка воздуха вместе с болью при дыхании, кровью в мокроте или обмороком требует экстренной медицинской оценки. Такое сочетание встречается при опасных состояниях, включая тромбоэмболию лёгочной артерии; это правило срочности, а не диагноз.",
                matchedConceptIds = peCompanions + "dyspnea"
            )
        } else if (sudden && atRest) {
            flags += ComplaintTriageFlag(
                id = "sudden_dyspnea_at_rest",
                level = ComplaintTriageLevel.EMERGENCY,
                title = "Внезапная одышка в покое требует срочной оценки",
                message = "Внезапная выраженная нехватка воздуха в покое может быть признаком серьёзного состояния. Нужна экстренная медицинская оценка, независимо от количества признаков для модели.",
                matchedConceptIds = setOf("dyspnea")
            )
        } else if (sudden || atRest) {
            flags += ComplaintTriageFlag(
                id = "dyspnea_review",
                level = ComplaintTriageLevel.MEDICAL_REVIEW,
                title = "Одышка требует внимания",
                message = "Внезапная одышка или одышка в покое требует медицинской оценки. При выраженном затруднении дыхания обращайтесь за экстренной помощью.",
                matchedConceptIds = setOf("dyspnea")
            )
        }
    }

    private fun addHemoptysisFlags(
        concepts: Set<String>,
        flags: MutableList<ComplaintTriageFlag>
    ) {
        if ("hemoptysis" !in concepts) return
        if (flags.any { it.id == "pulmonary_embolism_warning_pattern" }) return

        flags += ComplaintTriageFlag(
            id = "hemoptysis_review",
            level = ComplaintTriageLevel.MEDICAL_REVIEW,
            title = "Кровь в мокроте требует медицинской оценки",
            message = "Кровохарканье имеет разные причины и требует медицинской оценки. Если одновременно появилась внезапная одышка, боль при дыхании, обморок или резкое ухудшение состояния — обращайтесь за экстренной помощью.",
            matchedConceptIds = setOf("hemoptysis")
        )
    }

    private fun addBloodPressureFlags(
        concepts: Set<String>,
        answers: Map<String, String>,
        flags: MutableList<ComplaintTriageFlag>
    ) {
        if ("high_bp" in concepts) {
            val severeReading = answerEquals(answers, "high_bp", "measured_bp", "180/120 или выше")
            val concerning = concepts.any { it in hypertensiveEmergencySymptoms }
            if (severeReading && concerning) {
                flags += ComplaintTriageFlag(
                    id = "hypertensive_emergency_pattern",
                    level = ComplaintTriageLevel.EMERGENCY,
                    title = "Очень высокое давление с опасными симптомами",
                    message = "Давление около 180/120 или выше вместе с болью в груди, одышкой, болью в спине или новыми неврологическими симптомами требует экстренной медицинской помощи.",
                    matchedConceptIds = concepts.intersect(hypertensiveEmergencySymptoms) + "high_bp"
                )
            } else if (severeReading) {
                flags += ComplaintTriageFlag(
                    id = "severe_hypertension_review",
                    level = ComplaintTriageLevel.MEDICAL_REVIEW,
                    title = "Очень высокое давление требует повторного измерения и связи с врачом",
                    message = "Если давление действительно около 180/120 или выше, повторите измерение после короткого отдыха и свяжитесь с медицинским специалистом. При появлении боли в груди, одышки, слабости/онемения, нарушения зрения или речи нужна экстренная помощь.",
                    matchedConceptIds = setOf("high_bp")
                )
            }
        }

        if ("low_bp" in concepts) {
            val symptomaticAnswer = answerIsYes(answers, "low_bp", "symptomatic")
            val concerning = concepts.any { it in hypotensionSymptoms }
            if (symptomaticAnswer || concerning) {
                flags += ComplaintTriageFlag(
                    id = "symptomatic_hypotension",
                    level = ComplaintTriageLevel.MEDICAL_REVIEW,
                    title = "Низкое давление сопровождается симптомами",
                    message = "Низкое давление само по себе не всегда опасно, но вместе с головокружением, обмороком, спутанностью, нарушением зрения, выраженной слабостью или сердцебиением требует медицинской оценки. Значение ниже 90/60 — один из ориентиров, но отсутствие точной цифры не отменяет оценку симптомов.",
                    matchedConceptIds = concepts.intersect(hypotensionSymptoms) + "low_bp"
                )
            }
        }
    }

    private fun addSyncopeFlags(
        complaints: List<String>,
        concepts: Set<String>,
        answers: Map<String, String>,
        flags: MutableList<ComplaintTriageFlag>
    ) {
        if ("syncope" !in concepts) return

        val highRiskAnswer = answerIsYes(answers, "syncope", "during_exertion") ||
            answerIsYes(answers, "syncope", "while_supine") ||
            answerIsYes(answers, "syncope", "preceded_by_palpitations")
        val highRiskText = complaints.any { complaint ->
            val local = FreeTextSymptomExtractor.extract(listOf(complaint)).conceptIds
            "syncope" in local && hasHighRiskSyncopeMarker(complaint)
        }

        flags += ComplaintTriageFlag(
            id = if (highRiskAnswer || highRiskText) "high_risk_syncope" else "syncope_review",
            level = ComplaintTriageLevel.MEDICAL_REVIEW,
            title = if (highRiskAnswer || highRiskText) {
                "Обморок содержит признак повышенного риска"
            } else {
                "Потеря сознания требует медицинской оценки"
            },
            message = if (highRiskAnswer || highRiskText) {
                "Обморок при нагрузке, лёжа или сразу после выраженного сердцебиения относится к признакам повышенного риска и требует скорой очной оценки. Если эпизод произошёл только что или состояние остаётся плохим, обращайтесь за экстренной помощью."
            } else {
                "Причину потери сознания нельзя безопасно определить по приложению. Рекомендуется медицинская оценка; при повторном обмороке или резком ухудшении состояния нужна экстренная помощь."
            },
            matchedConceptIds = setOf("syncope")
        )
    }

    private fun answerIsYes(
        answers: Map<String, String>,
        conceptId: String,
        questionId: String
    ): Boolean = answerEquals(answers, conceptId, questionId, "да")

    private fun answerEquals(
        answers: Map<String, String>,
        conceptId: String,
        questionId: String,
        expected: String
    ): Boolean = answers["concept:$conceptId:$questionId"]?.equals(expected, ignoreCase = true) == true

    private fun hasSuddenMarker(value: String): Boolean {
        val normalized = normalize(value)
        return listOf("внезапно", "резко", "неожиданно", "только что", "вдруг").any(normalized::contains)
    }

    private fun hasSuddenSeverePainMarker(value: String): Boolean {
        val normalized = normalize(value)
        val sudden = listOf("внезапно", "неожиданно", "только что", "вдруг").any(normalized::contains)
        val severe = listOf(
            "сильная боль",
            "сильные боли",
            "очень сильн",
            "резкая боль",
            "нестерпим",
            "кинжальн",
            "разрывающ",
            "максимальн"
        ).any(normalized::contains)
        return sudden && severe
    }

    private fun hasAcuteChestMarker(value: String): Boolean {
        val normalized = normalize(value)
        return hasSuddenMarker(normalized) || listOf(
            "сильная боль",
            "сильные боли",
            "резкая боль",
            "нарастающая боль",
            "боль усиливается",
            "не проходит",
            "болит сейчас",
            "давит сейчас",
            "сжимает сейчас"
        ).any(normalized::contains)
    }

    private fun hasHighRiskSyncopeMarker(value: String): Boolean {
        val normalized = normalize(value)
        return listOf(
            "при нагрузке",
            "во время нагрузки",
            "во время бега",
            "во время тренировки",
            "лежа",
            "во сне",
            "после сердцебиения",
            "после перебоев"
        ).any(normalized::contains)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()
}
