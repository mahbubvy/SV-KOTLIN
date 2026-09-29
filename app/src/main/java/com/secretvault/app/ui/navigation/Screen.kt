package com.secretvault.app.ui.navigation

sealed class Screen(val route: String) {
    object DecoyWeather : Screen("decoy_weather")
    object Auth : Screen("auth")
    object VaultHome : Screen("vault_home")
    object Camera : Screen("camera")
    object MediaViewer : Screen("media_viewer/{albumId}/{initialMediaId}") {
        fun createRoute(albumId: String?, initialMediaId: String): String {
            val encodedAlbum = albumId ?: "ALL"
            return "media_viewer/$encodedAlbum/$initialMediaId"
        }
    }
}
