package my.diplom.aritmia

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.SymptomEntity
import my.diplom.aritmia.ui.screen.SharedViewModel
import my.diplom.aritmia.ui.screen.admin.AdminScreen
import my.diplom.aritmia.ui.screen.clarify.ClarifyScreen
import my.diplom.aritmia.ui.screen.clarify.resolveSymptomTerm
import my.diplom.aritmia.ui.screen.doctor.DoctorScreen
import my.diplom.aritmia.ui.screen.login.LoginScreen
import my.diplom.aritmia.ui.screen.result.ResultScreen
import my.diplom.aritmia.ui.screen.symptoms.SymptomsScreen
import javax.inject.Inject

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.O)
class MainActivity : ComponentActivity() {

    @Inject lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val savedPatientId = prefs.getInt("current_patient_id", -1).takeIf { it != -1 }
        val savedDoctorId = prefs.getInt("current_doctor_id", -1).takeIf { it != -1 }
        val savedAdminId = prefs.getInt("current_admin_id", -1).takeIf { it != -1 }

        setContent {
            val navController = rememberNavController()
            val scope = rememberCoroutineScope()
            val sharedViewModel: SharedViewModel = viewModel()
            var validatedStartDestination by remember { mutableStateOf<String?>(null) }

            fun clearPersistedSession() {
                prefs.edit()
                    .remove("current_patient_id")
                    .remove("current_doctor_id")
                    .remove("current_admin_id")
                    .apply()
            }

            LaunchedEffect(Unit) {
                validatedStartDestination = when {
                    savedPatientId != null -> {
                        val patient = db.userDao().getUserByIdAndRole(savedPatientId, Role.PATIENT)
                        if (patient != null && patient.isActive) {
                            sharedViewModel.setData(emptyList(), patient.id)
                            "symptoms"
                        } else {
                            clearPersistedSession()
                            "login"
                        }
                    }
                    savedDoctorId != null -> {
                        val doctor = db.userDao().getUserByIdAndRole(savedDoctorId, Role.DOCTOR)
                        if (doctor != null && doctor.isActive) {
                            "doctor"
                        } else {
                            clearPersistedSession()
                            "login"
                        }
                    }
                    savedAdminId != null -> {
                        val admin = db.userDao().getUserByIdAndRole(savedAdminId, Role.ADMIN)
                        if (admin != null && admin.isActive) {
                            "admin"
                        } else {
                            clearPersistedSession()
                            "login"
                        }
                    }
                    else -> "login"
                }
            }

            val onLogout = {
                clearPersistedSession()
                sharedViewModel.clearData()
                navController.navigate("login") { popUpTo(0) { inclusive = true } }
            }

            validatedStartDestination?.let { startDestination ->
                NavHost(navController = navController, startDestination = startDestination) {
                    composable("login") {
                        LoginScreen(
                            onLoginSuccess = { user ->
                                when (user.role) {
                                    Role.PATIENT -> {
                                        sharedViewModel.setData(emptyList(), user.id)
                                        navController.navigate("symptoms") {
                                            popUpTo("login") { inclusive = true }
                                        }
                                    }
                                    Role.DOCTOR -> navController.navigate("doctor") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                    Role.ADMIN -> navController.navigate("admin") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            },
                            navController = navController
                        )
                    }

                    composable("symptoms") {
                        var rules by remember { mutableStateOf<List<RuleEntity>>(emptyList()) }
                        LaunchedEffect(Unit) { rules = db.ruleDao().getAllRules() }

                        SymptomsScreen(
                            onDiagnose = { symptomList ->
                                sharedViewModel.setData(symptomList, sharedViewModel.userId.value)
                                val hasQuestions = symptomList.any { symptom ->
                                    rules.any { rule ->
                                        symptom.contains(rule.symptomKey, ignoreCase = true) &&
                                            rule.clarifyingQuestions != null
                                    }
                                }
                                if (hasQuestions) navController.navigate("clarify")
                                else navController.navigate("result")
                            },
                            onLogout = onLogout
                        )
                    }

                    composable("symptoms-followup") {
                        var rules by remember { mutableStateOf<List<RuleEntity>>(emptyList()) }
                        LaunchedEffect(Unit) { rules = db.ruleDao().getAllRules() }

                        SymptomsScreen(
                            initialSymptoms = sharedViewModel.symptoms.value,
                            onDiagnose = { symptomList ->
                                sharedViewModel.setData(symptomList, sharedViewModel.userId.value)
                                val hasQuestions = symptomList.any { symptom ->
                                    rules.any { rule ->
                                        symptom.contains(rule.symptomKey, ignoreCase = true) &&
                                            rule.clarifyingQuestions != null
                                    }
                                }
                                if (hasQuestions) navController.navigate("clarify")
                                else navController.navigate("result")
                            },
                            onLogout = onLogout
                        )
                    }

                    composable("clarify") {
                        val symptoms = sharedViewModel.symptoms.value
                        val userId = sharedViewModel.userId.value
                        val initialAnswers = sharedViewModel.answers.value

                        if (symptoms.isEmpty() || userId == -1) {
                            LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                        } else {
                            ClarifyScreen(
                                symptoms = symptoms,
                                userId = userId,
                                initialAnswers = initialAnswers,
                                onFinish = { answers ->
                                    scope.launch {
                                        val patient = db.userDao().getPatientById(userId) ?: return@launch
                                        val rules = db.ruleDao().getAllRules()

                                        val medTerms = symptoms.mapNotNull { s ->
                                            resolveSymptomTerm(s, rules, answers).medicalTerm
                                        }.joinToString(", ")

                                        db.symptomDao().insert(
                                            SymptomEntity(
                                                userInput = symptoms.joinToString(". "),
                                                medicalTerm = medTerms.ifBlank { null },
                                                probability = 0,
                                                patientId = patient.id,
                                                clarifyingAnswers = answers.entries
                                                    .filter { it.value.any { a -> a.isNotBlank() } }
                                                    .joinToString(";") {
                                                        "${it.key}=${it.value.joinToString(",")}"
                                                    },
                                                nnProbability = null
                                            )
                                        )
                                        sharedViewModel.updateAnswers(answers)
                                        navController.navigate("result")
                                    }
                                },
                                onLogout = onLogout
                            )
                        }
                    }

                    composable("result") {
                        val userId = sharedViewModel.userId.value
                        if (userId == -1) {
                            LaunchedEffect(Unit) {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                        } else {
                            ResultScreen(
                                userId = userId,
                                onLogout = onLogout,
                                onBack = { navController.popBackStack("symptoms", inclusive = false) },
                                onContinue = { navController.navigate("symptoms-followup") },
                                navController = navController,
                                sharedViewModel = sharedViewModel
                            )
                        }
                    }

                    composable("doctor") { DoctorScreen(onLogout = onLogout) }
                    composable("admin") { AdminScreen(onLogout = onLogout) }
                }
            }
        }
    }
}
