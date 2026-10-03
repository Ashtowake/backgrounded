package dev.backgrounded.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.backgrounded.ui.album.AlbumScreen
import dev.backgrounded.ui.albums.AlbumsScreen
import dev.backgrounded.ui.albumsettings.AlbumSettingsScreen
import dev.backgrounded.ui.editor.EditorScreen
import dev.backgrounded.ui.history.HistoryScreen
import dev.backgrounded.ui.nav.AlbumRoute
import dev.backgrounded.ui.nav.AlbumSettingsRoute
import dev.backgrounded.ui.nav.AlbumsRoute
import dev.backgrounded.ui.nav.EditorRoute
import dev.backgrounded.ui.nav.HistoryRoute
import dev.backgrounded.ui.nav.SettingsRoute
import dev.backgrounded.ui.settings.SettingsScreen

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = AlbumsRoute, modifier = modifier) {
        composable<AlbumsRoute> {
            AlbumsScreen(
                onOpenAlbum = { albumId -> navController.navigate(AlbumRoute(albumId)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<AlbumRoute> { entry ->
            val route = entry.toRoute<AlbumRoute>()
            AlbumScreen(
                onBack = { navController.popBackStack() },
                onEditPair = { pairId -> navController.navigate(EditorRoute(pairId)) },
                onOpenSettings = { navController.navigate(AlbumSettingsRoute(route.albumId)) },
            )
        }
        composable<EditorRoute> {
            EditorScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AlbumSettingsRoute> {
            AlbumSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenHistory = { navController.navigate(HistoryRoute) },
            )
        }
        composable<HistoryRoute> {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
