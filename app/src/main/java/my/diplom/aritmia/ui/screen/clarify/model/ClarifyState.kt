package my.diplom.aritmia.ui.screen.clarify.model

import androidx.compose.runtime.Immutable
import my.diplom.aritmia.data.RuleEntity

@Immutable
data class ClarifyScreenState(
    val symptoms: List<String> = emptyList(),
    val userId: Int = -1,
    val rules: List<RuleEntity> = emptyList(),
    val answers: Map<String, MutableList<String>> = emptyMap(),
    val isLoading: Boolean = true,
    val navigateToFinish: Boolean = false,
    val logout: Boolean = false
)