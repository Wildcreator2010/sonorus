package com.wildcreator.sonorus.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wildcreator.sonorus.ui.chat.ChatScreen
import com.wildcreator.sonorus.ui.chat.ConversationListScreen
import com.wildcreator.sonorus.ui.model.ModelListScreen
import com.wildcreator.sonorus.ui.provider.ProviderEditScreen
import com.wildcreator.sonorus.ui.provider.ProviderListScreen
import com.wildcreator.sonorus.ui.settings.SettingsScreen

object Routes {
    const val CONVERSATIONS = "conversations"
    const val CHAT = "chat/{conversationId}"
    const val PROVIDERS = "providers"
    const val PROVIDER_EDIT = "provider_edit?providerId={providerId}"
    const val MODELS = "models/{providerId}"
    const val SETTINGS = "settings"

    fun chat(conversationId: Long) = "chat/$conversationId"
    fun providerEdit(providerId: Long? = null) =
        if (providerId == null) "provider_edit" else "provider_edit?providerId=$providerId"
    fun models(providerId: Long) = "models/$providerId"
}

@Composable
fun WildNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.CONVERSATIONS) {

        composable(Routes.CONVERSATIONS) {
            ConversationListScreen(
                onOpenChat = { navController.navigate(Routes.chat(it)) },
                onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.LongType })
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PROVIDERS) {
            ProviderListScreen(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.providerEdit()) },
                onEdit = { navController.navigate(Routes.providerEdit(it)) },
                onModels = { navController.navigate(Routes.models(it)) }
            )
        }

        composable(
            route = Routes.PROVIDER_EDIT,
            arguments = listOf(
                navArgument("providerId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            ProviderEditScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.MODELS,
            arguments = listOf(navArgument("providerId") { type = NavType.LongType })
        ) {
            ModelListScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
