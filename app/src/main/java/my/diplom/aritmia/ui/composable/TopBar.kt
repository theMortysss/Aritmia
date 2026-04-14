package my.diplom.aritmia.ui.composable

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    onLogout: () -> Unit,
    title: String = "Медицинское приложение",
) {
    var showDialog by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title) },
        actions = {
            TextButton(onClick = { showDialog = true }) {
                Text("Выйти", color = MaterialTheme.colorScheme.onPrimary)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary
        )
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Выход") },
            text = { Text("Вы уверены, что хотите выйти?") },
            confirmButton = {
                Button(onClick = {
                    onLogout()
                    showDialog = false
                }) {
                    Text("Да")
                }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) {
                    Text("Нет")
                }
            }
        )
    }
}