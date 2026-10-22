package com.hazyoctophant.cardvault.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Game
import com.hazyoctophant.cardvault.ui.screens.BinderScreen
import com.hazyoctophant.cardvault.ui.screens.BindersScreen
import com.hazyoctophant.cardvault.ui.screens.CardDetailScreen
import com.hazyoctophant.cardvault.ui.screens.CollectionScreen
import com.hazyoctophant.cardvault.ui.screens.DeckEditorScreen
import com.hazyoctophant.cardvault.ui.screens.DecksScreen
import com.hazyoctophant.cardvault.ui.screens.HomeScreen
import com.hazyoctophant.cardvault.ui.screens.MtgDatabaseScreen
import com.hazyoctophant.cardvault.ui.screens.PriceSheetScreen
import com.hazyoctophant.cardvault.ui.screens.ScanScreen
import com.hazyoctophant.cardvault.ui.screens.SearchScreen
import com.hazyoctophant.cardvault.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val COLLECTION = "collection"
    const val SCAN = "scan"
    const val BINDERS = "binders"
    const val DECKS = "decks"
    const val PRICES = "prices"
    const val SETTINGS = "settings"
    const val MTG_DATABASE = "mtgdb"

    fun card(id: String) = "card/${Uri.encode(id)}"
    fun binder(id: Long) = "binder/$id"
    fun deck(id: Long) = "deck/$id"

    /** Manual card search; adds to the collection, or to a deck when [deckId] is given. */
    fun search(game: Game? = null, query: String = "", deckId: Long? = null, zone: DeckZone = DeckZone.MAIN) =
        "search?game=${game?.name.orEmpty()}&q=${Uri.encode(query)}&deck=${deckId ?: -1}&zone=${zone.name}"
}

/** Binder ids for the virtual "all cards" and "not in a binder" views. */
const val ALL_CARDS_BINDER = -1L
const val UNFILED_BINDER = -2L

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Dashboard),
    Tab(Routes.COLLECTION, "Collection", Icons.Default.Collections),
    Tab(Routes.SCAN, "Scan", Icons.Default.CameraAlt),
    Tab(Routes.BINDERS, "Binders", Icons.AutoMirrored.Filled.MenuBook),
    Tab(Routes.DECKS, "Decks", Icons.Default.Layers),
)

@Composable
fun CardVaultNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.COLLECTION) { CollectionScreen(nav) }
            composable(Routes.SCAN) { ScanScreen(nav) }
            composable(Routes.BINDERS) { BindersScreen(nav) }
            composable(Routes.DECKS) { DecksScreen(nav) }
            composable(Routes.PRICES) { PriceSheetScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.MTG_DATABASE) { MtgDatabaseScreen(nav) }
            composable("card/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                CardDetailScreen(nav, Uri.decode(it.arguments?.getString("id").orEmpty()))
            }
            composable("binder/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                BinderScreen(nav, it.arguments?.getLong("id") ?: ALL_CARDS_BINDER)
            }
            composable("deck/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                DeckEditorScreen(nav, it.arguments?.getLong("id") ?: 0L)
            }
            composable(
                "search?game={game}&q={q}&deck={deck}&zone={zone}",
                listOf(
                    navArgument("game") { type = NavType.StringType; defaultValue = "" },
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                    navArgument("deck") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("zone") { type = NavType.StringType; defaultValue = DeckZone.MAIN.name },
                ),
            ) {
                val args = it.arguments
                SearchScreen(
                    nav = nav,
                    initialGame = args?.getString("game")?.takeIf { g -> g.isNotEmpty() }?.let(Game::valueOf),
                    initialQuery = Uri.decode(args?.getString("q").orEmpty()),
                    deckId = args?.getLong("deck")?.takeIf { d -> d >= 0 },
                    zone = DeckZone.valueOf(args?.getString("zone") ?: DeckZone.MAIN.name),
                )
            }
        }
    }
}

fun NavController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
