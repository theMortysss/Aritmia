package my.diplom.aritmia.ui.screen.doctor

internal fun doctorTriagePriority(level: String): Int = when (level) {
    "EMERGENCY" -> 2
    "MEDICAL_REVIEW" -> 1
    else -> 0
}

internal fun doctorTriageLabel(level: String): String? = when (level) {
    "EMERGENCY" -> "Экстренная оценка"
    "MEDICAL_REVIEW" -> "Медицинская оценка"
    else -> null
}
