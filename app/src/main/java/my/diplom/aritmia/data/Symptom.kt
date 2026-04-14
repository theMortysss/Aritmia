package my.diplom.aritmia.data

data class Symptom(
    val userInput: String,
    val medicalTerm: String?,
    val probability: Int,
    val patientPhone: String? = null
)