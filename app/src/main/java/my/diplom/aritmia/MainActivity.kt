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
import my.diplom.aritmia.nn.NetworkRepository
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
    @Inject lateinit var networkRepository: NetworkRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs          = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val savedPatientId = prefs.getInt("current_patient_id", -1).takeIf { it != -1 }
        val savedDoctorId  = prefs.getInt("current_doctor_id",  -1).takeIf { it != -1 }
        val savedAdminId   = prefs.getInt("current_admin_id",   -1).takeIf { it != -1 }

        setContent {
            val navController    = rememberNavController()
            val scope            = rememberCoroutineScope()
            val sharedViewModel: SharedViewModel = viewModel()

            LaunchedEffect(Unit) {
                if (savedPatientId != null) {
                    val patient = db.userDao().getPatientById(savedPatientId)
                    if (patient != null) sharedViewModel.setData(emptyList(), patient.id)
                    else prefs.edit().remove("current_patient_id").apply()
                }
            }

            val startDestination = when {
                savedPatientId != null -> "symptoms"
                savedDoctorId  != null -> "doctor"
                savedAdminId   != null -> "admin"
                else                   -> "login"
            }

            val onLogout = {
                prefs.edit()
                    .remove("current_patient_id")
                    .remove("current_doctor_id")
                    .remove("current_admin_id")
                    .apply()
                sharedViewModel.clearData()
                navController.navigate("login") { popUpTo(0) { inclusive = true } }
            }

            NavHost(navController = navController, startDestination = startDestination) {

                // ── Login ──────────────────────────────────────────────────────
                composable("login") {
                    LoginScreen(
                        onLoginSuccess = { user ->
                            scope.launch {
                                if (!networkRepository.isReady()) {
                                    networkRepository.initialize(db.ruleDao().getAllRules())
                                }
                            }
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
                                Role.ADMIN  -> navController.navigate("admin") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        },
                        navController = navController
                    )
                }

                // ── Symptoms ───────────────────────────────────────────────────
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
                            else             navController.navigate("result")
                        },
                        onLogout = onLogout
                    )
                }

                // ── Clarify ────────────────────────────────────────────────────
                composable("clarify") {
                    val symptoms       = sharedViewModel.symptoms.value
                    val userId         = sharedViewModel.userId.value
                    val initialAnswers = sharedViewModel.answers.value

                    if (symptoms.isEmpty() || userId == -1) {
                        LaunchedEffect(Unit) {
                            navController.navigate("login") { popUpTo(0) { inclusive = true } }
                        }
                    } else {
                        ClarifyScreen(
                            symptoms       = symptoms,
                            userId         = userId,
                            initialAnswers = initialAnswers,
                            onFinish       = { answers ->
                                scope.launch {
                                    val patient = db.userDao().getPatientById(userId) ?: return@launch
                                    val rules   = db.ruleDao().getAllRules()

                                    val nnRaw  = networkRepository.predict(symptoms)
                                    val nnProb = nnRaw?.let { (it * 100).toInt().coerceIn(0, 100) } ?: 0

                                    val medTerms = symptoms.mapNotNull { s ->
                                        resolveSymptomTerm(s, rules, answers).medicalTerm
                                    }.joinToString(", ")

                                    db.symptomDao().insert(
                                        SymptomEntity(
                                            userInput         = symptoms.joinToString(". "),
                                            medicalTerm       = medTerms.ifBlank { null },
                                            probability       = nnProb,
                                            patientId         = userId,
                                            clarifyingAnswers = answers.entries
                                                .filter { it.value.any { a -> a.isNotBlank() } }
                                                .joinToString(";") {
                                                    "${it.key}=${it.value.joinToString(",")}"
                                                },
                                            nnProbability     = nnProb
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

                // ── Result ─────────────────────────────────────────────────────
                composable("result") {
                    val userId = sharedViewModel.userId.value
                    if (userId == -1) {
                        LaunchedEffect(Unit) {
                            navController.navigate("login") { popUpTo(0) { inclusive = true } }
                        }
                    } else {
                        ResultScreen(
                            userId          = userId,
                            onLogout        = onLogout,
                            onBack          = { navController.popBackStack("symptoms", inclusive = false) },
                            navController   = navController,
                            sharedViewModel = sharedViewModel
                        )
                    }
                }

                // ── Doctor ─────────────────────────────────────────────────────
                composable("doctor") { DoctorScreen(onLogout = onLogout) }

                // ── Admin ──────────────────────────────────────────────────────
                composable("admin") { AdminScreen(onLogout = onLogout) }
            }
        }
    }
}
