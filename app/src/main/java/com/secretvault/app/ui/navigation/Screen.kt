package com.secretvault.app.ui.navigation

sealed class Screen(val route: String) {
    object DecoyWeather : Screen("decoy_weather")
    object Auth : Screen("auth")
    object VaultHome : Screen("vault_home")
    object Settings : Screen("settings")
    object Camera : Screen("camera")
    object MediaViewer : Screen("media_viewer/{albumId}/{initialMediaId}") {
        fun createRoute(albumId: String?, initialMediaId: String): String {
            val encodedAlbum = albumId ?: "ALL"
            return "media_viewer/$encodedAlbum/$initialMediaId"
        }
    }
    object VideoEditor : Screen("video_editor/{mediaId}") {
        fun createRoute(mediaId: String): String = "video_editor/$mediaId"
    }
}
