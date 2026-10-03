package dev.backgrounded.ui.nav

import kotlinx.serialization.Serializable

@Serializable
data object AlbumsRoute

@Serializable
data class AlbumRoute(val albumId: Long)

@Serializable
data class EditorRoute(val pairId: Long)

@Serializable
data class AlbumSettingsRoute(val albumId: Long)

@Serializable
data object SettingsRoute

@Serializable
data object HistoryRoute
