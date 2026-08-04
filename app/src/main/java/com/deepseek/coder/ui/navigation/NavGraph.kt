package com.deepseek.coder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.deepseek.coder.data.credentials.CredentialRepository
import com.deepseek.coder.ui.screens.ChatScreen
import com.deepseek.coder.ui.screens.EditorScreen
import com.deepseek.coder.ui.screens.SessionListScreen
import com.deepseek.coder.ui.screens.SetupScreen
import com.deepseek.coder.ui.screens.SettingsScreen
import com.deepseek.coder.ui.screens.SkillManagementScreen
import com.deepseek.coder.ui.screens.UserSkillEditorScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Composable
fun DeepCoderNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(DeepCoderScreens.Setup.route) {
            SetupScreen(
                onNavigateToChat = {
                    navController.navigate(DeepCoderScreens.Chat.route()) {
                        popUpTo(DeepCoderScreens.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = DeepCoderScreens.Chat.route,
            arguments = listOf(navArgument("sessionId") {
                type = NavType.StringType; defaultValue = "new"
            })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            ChatScreen(
                sessionId = sessionId,
                onNavigateToEditor = { navController.navigate(DeepCoderScreens.Editor.route) },
                onNavigateToSessions = { navController.navigate(DeepCoderScreens.SessionList.route) },
                onNavigateToSettings = { navController.navigate(DeepCoderScreens.Settings.route) },
                onNavigateToSkills = { navController.navigate(DeepCoderScreens.SkillManagement.route) }
            )
        }

        composable(DeepCoderScreens.Editor.route) {
            EditorScreen()
        }

        composable(DeepCoderScreens.SessionList.route) {
            SessionListScreen(
                onSessionClick = { sessionId ->
                    navController.navigate(DeepCoderScreens.Chat.route(sessionId)) {
                        popUpTo(DeepCoderScreens.Chat.route()) { inclusive = true }
                    }
                }
            )
        }

        composable(DeepCoderScreens.Settings.route) {
            SettingsScreen()
        }

        composable(DeepCoderScreens.SkillManagement.route) {
            SkillManagementScreen(
                onBack = { navController.popBackStack() },
                onNavigateToEditor = { skillId ->
                    navController.navigate(DeepCoderScreens.SkillEditor.route(skillId))
                }
            )
        }

        composable(
            route = DeepCoderScreens.SkillEditor.route,
            arguments = listOf(navArgument("skillId") {
                type = NavType.StringType; defaultValue = "new"
            })
        ) {
            UserSkillEditorScreen(
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Simple ViewModel exposing first-launch routing decision.
 * CredentialRepository currently is a stub; real impl comes in MS1.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    credentialRepository: CredentialRepository
) : ViewModel() {
    val startDestination: StateFlow<String> =
        credentialRepository.hasApiKey
            .map { hasKey ->
                if (hasKey) DeepCoderScreens.Chat.route() else DeepCoderScreens.Setup.route
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DeepCoderScreens.Setup.route
            )
}
