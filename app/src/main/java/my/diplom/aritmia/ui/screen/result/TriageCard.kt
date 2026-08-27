package my.diplom.aritmia.ui.screen.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.diplom.aritmia.diagnosis.ComplaintTriageAssessment
import my.diplom.aritmia.diagnosis.ComplaintTriageLevel

@Composable
fun ComplaintTriageCard(assessment: ComplaintTriageAssessment) {
    if (assessment.level == ComplaintTriageLevel.NONE || assessment.flags.isEmpty()) return

    val emergency = assessment.level == ComplaintTriageLevel.EMERGENCY
    val container = if (emergency) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = if (emergency) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (emergency) "Нужна срочная медицинская оценка" else "Рекомендуется медицинская оценка",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = content
            )
            if (emergency) {
                Text(
                    "Не ждите, пока приложение соберёт больше признаков для модели. Если состояние происходит сейчас, ухудшается или вы сомневаетесь в безопасности ожидания — обращайтесь за экстренной медицинской помощью.",
                    color = content
                )
            }
            assessment.flags.forEach { flag ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(flag.title, fontWeight = FontWeight.SemiBold, color = content)
                    Text(flag.message, style = MaterialTheme.typography.bodySmall, color = content)
                }
            }
            Text(
                "Это правило срочности, а не диагноз и не вероятность заболевания.",
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
        }
    }
}
