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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
    navController: NavHostController = rememberNavController(),
    cameraEntryRequest: Int = 0
) {
    val isUnlocked by app.sessionManager.isUnlocked.collectAsState()

    LaunchedEffect(cameraEntryRequest) {
        // An edit in progress wins over the camera shortcut: stay in the editor.
        if (cameraEntryRequest > 0 && app.sessionManager.keepUnlocked.value && app.sessionManager.isUnlocked.value &&
            app.videoEditManager.draft == null) {
            navController.navigate(Screen.VaultHome.route) {
                popUpTo(navController.graph.id)
                launchSingleTop = true
            }
            navController.navigate(Screen.Camera.route) { launchSingleTop = true }
        }
    }

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
            // The vault locked mid-edit: go back to the editor, which picks up its draft.
            LaunchedEffect(Unit) {
                if (app.videoEditManager.draft != null) navController.navigate(Screen.VideoProject.route) { launchSingleTop = true }
            }
            val galleryViewModel = viewModel<com.secretvault.app.ui.gallery.GalleryViewModel>(
                factory = viewModelFactory {
                    initializer { com.secretvault.app.ui.gallery.GalleryViewModel(app.mediaRepository) }
                }
            )
            val albumsViewModel = viewModel<com.secretvault.app.ui.gallery.AlbumsViewModel>(
                factory = viewModelFactory {
                    initializer { com.secretvault.app.ui.gallery.AlbumsViewModel(app.albumRepository) }
                }
            )
            com.secretvault.app.ui.gallery.GalleryScreen(
                app = app,
                galleryViewModel = galleryViewModel,
                albumsViewModel = albumsViewModel,
                onMediaClick = { item ->
                    val activeAlbumId = galleryViewModel.uiState.value.activeAlbumId
                    navController.navigate(Screen.MediaViewer.createRoute(activeAlbumId, item.id, galleryViewModel.uiState.value.favoritesOnly))
                },
                onCameraClick = {
                    navController.navigate(Screen.Camera.route)
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onViewStreamClick = { navController.navigate(Screen.ViewStream.route) },
                onReceiveFilesClick = { navController.navigate(Screen.ReceiveFiles.route) },
                onVideoEditorClick = { navController.navigate(Screen.VideoProject.route) },
                onSendToDevice = { ids ->
                    navController.currentBackStackEntry?.savedStateHandle?.set(SEND_IDS_KEY, ArrayList(ids))
                    navController.navigate(Screen.SendFiles.route)
                },
                onLockClick = {
                    app.ephemeralShareManager.purgeAllSharedFiles(force = true)
                    app.sessionManager.lock()
                    navController.navigate(Screen.DecoyWeather.route) {
                        popUpTo(Screen.DecoyWeather.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            com.secretvault.app.ui.settings.SettingsScreen(
                pinManager = app.pinManager,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ViewStream.route) {
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            com.secretvault.app.ui.stream.StreamViewerScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.ReceiveFiles.route) {
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            com.secretvault.app.ui.transfer.ReceiveFilesScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.SendFiles.route) {
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            val ids = remember { navController.previousBackStackEntry?.savedStateHandle?.get<ArrayList<String>>(SEND_IDS_KEY).orEmpty() }
            com.secretvault.app.ui.transfer.SendFilesScreen(mediaIds = ids, onBack = { navController.popBackStack() })
        }

        composable(Screen.CameraStream.route) {
            if (!isUnlocked || !com.secretvault.app.BuildConfig.DEBUG) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            com.secretvault.app.ui.stream.CameraStreamScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Camera.route) {
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            val cameraViewModel = viewModel<com.secretvault.app.ui.camera.CameraViewModel>(
                factory = viewModelFactory {
                    initializer {
                        com.secretvault.app.ui.camera.CameraViewModel(
                            com.secretvault.app.core.camera.defaultVideoMode(
                                android.os.Build.MANUFACTURER, android.os.Build.MODEL, android.os.Build.DEVICE
                            )
                        )
                    }
                }
            )
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
            val favoritesOnly = rawAlbumId == "FAVORITES"
            val albumId = if (rawAlbumId == "ALL" || favoritesOnly) null else rawAlbumId
            val initialMediaId = backStackEntry.arguments?.getString("initialMediaId") ?: ""

            val viewerViewModel = remember(albumId, initialMediaId) {
                com.secretvault.app.ui.viewer.MediaViewerViewModel(
                    albumId = albumId,
                    initialMediaId = initialMediaId,
                    mediaRepository = app.mediaRepository,
                    albumRepository = app.albumRepository,
                    favoritesOnly = favoritesOnly
                )
            }

            com.secretvault.app.ui.viewer.MediaViewerScreen(
                app = app,
                viewModel = viewerViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onEditPhoto = { mediaId -> navController.navigate(Screen.PhotoEditor.createRoute(mediaId)) },
                onEditVideo = { mediaId ->
                    navController.navigate(Screen.VideoEditor.createRoute(mediaId))
                }
            )
        }

        composable(Screen.VideoProject.route) {
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            com.secretvault.app.ui.editor.VideoProjectScreen(app = app, onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.PhotoEditor.route,
            arguments = listOf(
                androidx.navigation.navArgument("mediaId") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
            val item by androidx.compose.runtime.produceState<com.secretvault.app.core.model.MediaItem?>(null, mediaId) {
                value = app.mediaRepository.getMediaById(mediaId)
            }
            val loaded = item
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
            } else {
                com.secretvault.app.ui.editor.PhotoEditorScreen(
                    app = app,
                    item = loaded,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Screen.VideoEditor.route,
            arguments = listOf(
                androidx.navigation.navArgument("mediaId") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(VaultDarkBg))
                return@composable
            }
            val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
            val item by androidx.compose.runtime.produceState<com.secretvault.app.core.model.MediaItem?>(null, mediaId) {
                value = app.mediaRepository.getMediaById(mediaId)
            }
            val loaded = item
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
            } else {
                com.secretvault.app.ui.editor.VideoEditorScreen(
                    app = app,
                    item = loaded,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/** Gallery selection handed to the Send to SV screen. */
private const val SEND_IDS_KEY = "send_media_ids"
