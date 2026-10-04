package com.secretvault.app.ui.navigation

sealed class Screen(val route: String) {
    object DecoyWeather : Screen("decoy_weather")
    object Auth : Screen("auth")
    object VaultHome : Screen("vault_home")
    object Settings : Screen("settings")
    object Camera : Screen("camera")
    object ViewStream : Screen("view_stream")
    object ReceiveFiles : Screen("receive_files")
    object SendFiles : Screen("send_files")
    object CameraStream : Screen("camera_stream_test")
    object MediaViewer : Screen("media_viewer/{albumId}/{initialMediaId}") {
        fun createRoute(albumId: String?, initialMediaId: String, favoritesOnly: Boolean = false): String {
            val encodedAlbum = if (favoritesOnly) "FAVORITES" else albumId ?: "ALL"
            return "media_viewer/$encodedAlbum/$initialMediaId"
        }
    }
    object VideoEditor : Screen("video_editor/{mediaId}") {
        fun createRoute(mediaId: String): String = "video_editor/$mediaId"
    }
}
