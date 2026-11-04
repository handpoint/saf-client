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
import com.handpoint.safclient.domain.model.total
import com.handpoint.safclient.ui.detail.SaleDetailRoute
import com.handpoint.safclient.ui.home.HomeRoute
import com.handpoint.safclient.ui.operations.OperationsRoute
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

    /** Done: always to Home, whatever screens the flow went through. */
    @Composable
    fun toHome(): () -> Unit = dropUnlessResumed { navController.popBackStack(Routes.HOME, inclusive = false) }

    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            HomeRoute(
                viewModel = hiltViewModel(),
                onNewSale = { open(Routes.operations()) },
                onSendNow = { open(Routes.SENDING) },
                onOpenSale = { open(Routes.sale(it)) },
                onOpenSettings = { open(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.AMOUNT,
            arguments = listOf(
                navArgument(Routes.AMOUNT_VALUE) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { SaleRoute(viewModel = hiltViewModel(), onDone = toHome(), onBack = back()) }
        composable(Routes.SENDING) { SendingRoute(viewModel = hiltViewModel(), onDone = back()) }
        composable(
            route = Routes.SALE,
            arguments = listOf(navArgument(Routes.SALE_REFERENCE) { type = NavType.StringType }),
        ) {
            SaleDetailRoute(
                viewModel = hiltViewModel(),
                onBack = back(),
                onRefund = { sale ->
                    open(Routes.refundSetup(sale.transactionId ?: sale.reference.value, sale.total.minorUnits, sale.isMoto))
                },
            )
        }
        composable(Routes.SETTINGS) { SettingsRoute(viewModel = hiltViewModel(), onBack = back()) }
        composable(
            route = Routes.OPERATIONS,
            arguments = listOf(
                navArgument(Routes.OPERATIONS_TXN) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(Routes.OPERATIONS_AMOUNT) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(Routes.OPERATIONS_MOTO) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            OperationsRoute(
                viewModel = hiltViewModel(),
                onContinue = { prefill -> open(Routes.amount(prefill)) },
                onBack = back(),
            )
        }
    }
}
