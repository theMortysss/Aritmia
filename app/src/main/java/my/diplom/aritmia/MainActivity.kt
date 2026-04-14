package my.diplom.aritmia

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import my.diplom.aritmia.data.AppDatabase
import my.diplom.aritmia.data.RuleEntity
import my.diplom.aritmia.data.SymptomEntity
import my.diplom.aritmia.ui.screen.clarify.ClarifyScreen
import my.diplom.aritmia.ui.screen.doctor.DoctorScreen
import my.diplom.aritmia.ui.screen.login.LoginScreen
import my.diplom.aritmia.ui.screen.result.ResultScreen
import my.diplom.aritmia.ui.screen.symptoms.SymptomsScreen
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import my.diplom.aritmia.data.Role
import my.diplom.aritmia.data.User
import my.diplom.aritmia.ui.screen.SharedViewModel
import my.diplom.aritmia.ui.screen.admin.AdminScreen
import javax.inject.Inject

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.O)
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var db: AppDatabase

    // test master
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val savedPatientId = sharedPreferences.getInt("current_patient_id", -1).takeIf { it != -1 }
        val savedDoctorId = sharedPreferences.getInt("current_doctor_id", -1).takeIf { it != -1 }
        val savedAdminId = sharedPreferences.getInt("current_admin_id", -1).takeIf { it != -1 }

        setContent {
            val navController = rememberNavController()
            val scope = rememberCoroutineScope()
            val sharedViewModel: SharedViewModel = viewModel()

            LaunchedEffect(Unit) {
                if (savedPatientId != null) {
                    val patient = db.userDao().getPatientById(savedPatientId)
                    if (patient != null) {
                        sharedViewModel.setData(emptyList(), patient.id)
                    } else {
                        with(sharedPreferences.edit()) {
                            remove("current_patient_id")
                            apply()
                        }
                    }
                }
            }

            val startDestination = when {
                savedPatientId != null -> "symptoms"
                savedDoctorId != null -> "doctor"
                savedAdminId != null -> "admin"
                else -> "login"
            }

            val onLogout = {
                with(sharedPreferences.edit()) {
                    remove("current_patient_id")
                    remove("current_doctor_id")
                    remove("current_admin_id")
                    apply()
                }
                sharedViewModel.clearData()
                navController.navigate("login") {
                    popUpTo(0) { inclusive = true }
                }
            }

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
                                Role.DOCTOR -> {
                                    navController.navigate("doctor") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                                Role.ADMIN -> {
                                    navController.navigate("admin") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            }
                        },
                        navController = navController
                    )
                }
                composable("symptoms") {
                    var rules by remember { mutableStateOf<List<RuleEntity>>(emptyList()) }
                    LaunchedEffect(Unit) {
                        rules = db.ruleDao().getAllRules()
                    }
                    SymptomsScreen(
                        onDiagnose = { symptomList ->
                            sharedViewModel.setData(symptomList, sharedViewModel.userId.value)
                            val hasQuestions = symptomList.any { symptom ->
                                rules.any { rule ->
                                    symptom.contains(rule.symptomKey, ignoreCase = true) && rule.clarifyingQuestions != null
                                }
                            }
                            if (hasQuestions) {
                                navController.navigate("clarify")
                            } else {
                                navController.navigate("result")
                            }
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
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    } else {
                        ClarifyScreen(
                            symptoms = symptoms,
                            userId = userId,
                            initialAnswers = initialAnswers,
                            onFinish = { diagnoses, answers ->
                                scope.launch {
                                    val patient = db.userDao().getPatientById(userId)
                                    if (patient != null) {
                                        db.symptomDao().insert(
                                            SymptomEntity(
                                                userInput = diagnoses.joinToString(". ") { it.userInput },
                                                medicalTerm = diagnoses.mapNotNull { it.medicalTerm }.joinToString(", "),
                                                probability = diagnoses.sumOf { it.probability },
                                                patientId = userId,
                                                clarifyingAnswers = answers.entries
                                                    .filter { it.value.any { answer -> answer.isNotBlank() } }
                                                    .joinToString(";") { "${it.key}=${it.value.joinToString(",")}" }
                                            )
                                        )
                                        sharedViewModel.updateAnswers(answers)
                                        navController.navigate("result")
                                    }
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
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    } else {
                        ResultScreen(
                            userId = userId,
                            onLogout = onLogout,
                            onBack = {
                                navController.popBackStack("symptoms", inclusive = false)
                            },
                            navController = navController,
                            sharedViewModel = sharedViewModel
                        )
                    }
                }
                composable("doctor") {
                    DoctorScreen(
                        onLogout = onLogout
                    )
                }
                composable("admin") {
                    AdminScreen(
                        onLogout = onLogout
                    )
                }
            }
        }
    }
}