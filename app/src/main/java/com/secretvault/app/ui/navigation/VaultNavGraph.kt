package com.secretvault.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.ui.auth.AuthScreen
import com.secretvault.app.ui.auth.AuthViewModel
import com.secretvault.app.ui.decoy.WeatherHomeScreen
import com.secretvault.app.ui.decoy.WeatherViewModel
import com.secretvault.app.ui.theme.VaultDarkBg

@Composable
fun VaultNavGraph(
    app: SecretVaultApp,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val isUnlocked by app.sessionManager.isUnlocked.collectAsState()

    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) {
            app.ephemeralShareManager.purgeAllSharedFiles()
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != null && currentRoute != Screen.DecoyWeather.route && currentRoute != Screen.Auth.route) {
                navController.navigate(Screen.DecoyWeather.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.DecoyWeather.route,
        modifier = modifier
    ) {
        composable(Screen.DecoyWeather.route) {
            val weatherViewModel = remember { WeatherViewModel(app.sessionManager) }
            WeatherHomeScreen(
                viewModel = weatherViewModel,
                onVisibilityTrigger = {
                    navController.navigate(Screen.Auth.route)
                },
                onCameraShortcut = {
                    if (app.sessionManager.isUnlocked.value) {
                        navController.navigate(Screen.Camera.route)
                    }
                }
            )
        }

        composable(Screen.Auth.route) {
            val authViewModel = remember { AuthViewModel(app.pinManager, app.sessionManager) }
            AuthScreen(
                viewModel = authViewModel,
                onAuthenticated = {
                    navController.navigate(Screen.VaultHome.route) {
                        popUpTo(Screen.DecoyWeather.route) { inclusive = false }
                    }
                },
                onClose = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.VaultHome.route) {
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            val galleryViewModel = remember { com.secretvault.app.ui.gallery.GalleryViewModel(app.mediaRepository) }
            val albumsViewModel = remember { com.secretvault.app.ui.gallery.AlbumsViewModel(app.albumRepository) }
            com.secretvault.app.ui.gallery.GalleryScreen(
                app = app,
                galleryViewModel = galleryViewModel,
                albumsViewModel = albumsViewModel,
                onMediaClick = { item ->
                    val activeAlbumId = galleryViewModel.uiState.value.activeAlbumId
                    navController.navigate(Screen.MediaViewer.createRoute(activeAlbumId, item.id))
                },
                onCameraClick = {
                    navController.navigate(Screen.Camera.route)
                },
                onLockClick = {
                    app.ephemeralShareManager.purgeAllSharedFiles()
                    app.sessionManager.lock()
                    navController.navigate(Screen.DecoyWeather.route) {
                        popUpTo(Screen.DecoyWeather.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Camera.route) {
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            val cameraViewModel = androidx.lifecycle.viewmodel.compose.viewModel<com.secretvault.app.ui.camera.CameraViewModel>()
            val returnToGallery: () -> Unit = {
                val popped = navController.popBackStack(Screen.VaultHome.route, false)
                if (!popped) {
                    navController.navigate(Screen.VaultHome.route) {
                        popUpTo(Screen.DecoyWeather.route) { inclusive = false }
                    }
                }
            }
            com.secretvault.app.ui.camera.CameraScreen(
                app = app,
                viewModel = cameraViewModel,
                onClose = returnToGallery,
                onNavigateToGallery = returnToGallery,
                onNavigateToViewer = { mediaId ->
                    navController.navigate(Screen.MediaViewer.createRoute(null, mediaId))
                }
            )
        }

        composable(
            route = Screen.MediaViewer.route,
            arguments = listOf(
                androidx.navigation.navArgument("albumId") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("initialMediaId") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            val rawAlbumId = backStackEntry.arguments?.getString("albumId")
            val albumId = if (rawAlbumId == "ALL") null else rawAlbumId
            val initialMediaId = backStackEntry.arguments?.getString("initialMediaId") ?: ""

            val viewerViewModel = remember(albumId, initialMediaId) {
                com.secretvault.app.ui.viewer.MediaViewerViewModel(
                    albumId = albumId,
                    initialMediaId = initialMediaId,
                    mediaRepository = app.mediaRepository,
                    albumRepository = app.albumRepository
                )
            }

            com.secretvault.app.ui.viewer.MediaViewerScreen(
                app = app,
                viewModel = viewerViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
