package com.deepseek.coder.ui.navigation

/**
 * Typed navigation destinations.
 * Route strings are URI-safe; simple string args are appended later via [routeWithArgs].
 */
sealed class DeepCoderScreens(val route: String) {
    object Setup : DeepCoderScreens("setup")
    object Chat : DeepCoderScreens("chat/{sessionId}") {
        fun route(sessionId: String? = null): String = if (sessionId.isNullOrBlank()) {
            "chat/new"
        } else {
            "chat/$sessionId"
        }
    }
    object Editor : DeepCoderScreens("editor")
    object SessionList : DeepCoderScreens("sessions")
    object Settings : DeepCoderScreens("settings")
}
