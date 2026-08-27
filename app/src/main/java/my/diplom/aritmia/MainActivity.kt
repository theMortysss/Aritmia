package my.diplom.aritmia

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import my.diplom.aritmia.ui.screen.clarify.hasClarificationQuestions
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
            val sharedViewModel: SharedViewModel = viewModel()
            var navigationRoot by remember { mutableStateOf<String?>(null) }
            var navigationSession by remember { mutableIntStateOf(0) }

            fun clearPersistedSession() {
                prefs.edit()
                    .remove("current_patient_id")
                    .remove("current_doctor_id")
                    .remove("current_admin_id")
                    .apply()
            }

            fun startNavigationSession(root: String) {
                navigationRoot = root
                navigationSession += 1
            }

            LaunchedEffect(Unit) {
                navigationRoot = when {
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
                        if (doctor != null && doctor.isActive) "doctor" else {
                            clearPersistedSession()
                            "login"
                        }
                    }
                    savedAdminId != null -> {
                        val admin = db.userDao().getUserByIdAndRole(savedAdminId, Role.ADMIN)
                        if (admin != null && admin.isActive) "admin" else {
                            clearPersistedSession()
                            "login"
                        }
                    }
                    else -> "login"
                }
            }

            navigationRoot?.let { root ->
                key(navigationSession) {
                    val navController = rememberNavController()
                    val scope = rememberCoroutineScope()

                    val onLogout = {
                        clearPersistedSession()
                        sharedViewModel.clearData()
                        startNavigationSession("login")
                    }

                    NavHost(
                        navController = navController,
                        startDestination = root,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        composable("login") {
                            LoginScreen(
                                onLoginSuccess = { user ->
                                    when (user.role) {
                                        Role.PATIENT -> {
                                            sharedViewModel.setData(emptyList(), user.id)
                                            startNavigationSession("symptoms")
                                        }
                                        Role.DOCTOR -> startNavigationSession("doctor")
                                        Role.ADMIN -> startNavigationSession("admin")
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
                                    if (hasClarificationQuestions(symptomList, rules)) {
                                        navController.navigate("clarify")
                                    } else {
                                        navController.navigate("result")
                                    }
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
                                    sharedViewModel.updateComplaintsPreservingAnswers(
                                        symptomList,
                                        sharedViewModel.userId.value
                                    )
                                    if (hasClarificationQuestions(symptomList, rules)) {
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
                                LaunchedEffect(symptoms.isEmpty(), userId) {
                                    clearPersistedSession()
                                    sharedViewModel.clearData()
                                    startNavigationSession("login")
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
                                            val medTerms = symptoms.mapNotNull { symptom ->
                                                resolveSymptomTerm(symptom, rules, answers).medicalTerm
                                            }.joinToString(", ")

                                            db.symptomDao().insert(
                                                SymptomEntity(
                                                    userInput = symptoms.joinToString(". "),
                                                    medicalTerm = medTerms.ifBlank { null },
                                                    probability = 0,
                                                    patientId = patient.id,
                                                    clarifyingAnswers = answers.entries
                                                        .filter { it.value.any { answer -> answer.isNotBlank() } }
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
                                LaunchedEffect(userId) {
                                    clearPersistedSession()
                                    sharedViewModel.clearData()
                                    startNavigationSession("login")
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
}
