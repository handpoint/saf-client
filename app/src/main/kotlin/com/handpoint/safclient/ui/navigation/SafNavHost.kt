package com.handpoint.safclient.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.handpoint.safclient.ui.detail.SaleDetailRoute
import com.handpoint.safclient.ui.home.HomeRoute
import com.handpoint.safclient.ui.sale.SaleRoute
import com.handpoint.safclient.ui.sending.SendingRoute
import com.handpoint.safclient.ui.settings.SettingsRoute

/** The app's screens and how they link. Start: [Routes.HOME]. */
@Composable
fun SafNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // launchSingleTop: a double tap opens a screen once, not twice.
    fun open(route: String) = navController.navigate(route) { launchSingleTop = true }

    /** Back from the current screen; ignored once it is leaving, so a double tap cannot pop Home too. */
    @Composable
    fun back(): () -> Unit = dropUnlessResumed { navController.popBackStack() }

    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            HomeRoute(
                viewModel = hiltViewModel(),
                onNewSale = { open(Routes.AMOUNT) },
                onSendNow = { open(Routes.SENDING) },
                onOpenSale = { open(Routes.sale(it)) },
                onOpenSettings = { open(Routes.SETTINGS) },
            )
        }
        composable(Routes.AMOUNT) { SaleRoute(viewModel = hiltViewModel(), onDone = back()) }
        composable(Routes.SENDING) { SendingRoute(viewModel = hiltViewModel(), onDone = back()) }
        composable(
            route = Routes.SALE,
            arguments = listOf(navArgument(Routes.SALE_REFERENCE) { type = NavType.StringType }),
        ) { SaleDetailRoute(viewModel = hiltViewModel(), onBack = back()) }
        composable(Routes.SETTINGS) { SettingsRoute(viewModel = hiltViewModel(), onBack = back()) }
    }
}
