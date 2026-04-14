package my.diplom.aritmia.ui.screen

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    private val _symptoms = mutableStateOf<List<String>>(emptyList())
    val symptoms: State<List<String>> = _symptoms

    private val _userId = mutableStateOf(-1)
    val userId: State<Int> = _userId

    private val _answers = mutableStateOf<Map<String, List<String>>>(emptyMap())
    val answers: State<Map<String, List<String>>> = _answers

    fun setData(symptoms: List<String>, userId: Int, newAnswers: Map<String, MutableList<String>> = emptyMap()) {
        _symptoms.value = symptoms
        _userId.value = userId
        _answers.value = newAnswers
    }

    fun updateAnswers(newAnswers: Map<String, List<String>>) {
        _answers.value = newAnswers
    }

    fun clearData() {
        _symptoms.value = emptyList()
        _userId.value = -1
        _answers.value = emptyMap()
    }
}
