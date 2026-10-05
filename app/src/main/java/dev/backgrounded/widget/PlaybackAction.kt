package dev.backgrounded.widget

import dev.backgrounded.R

enum class PlaybackAction(val label: String, val icon: Int, val viewId: Int) {
    PREVIOUS_ALBUM("Previous album", R.drawable.ic_control_album_previous, R.id.control_previous_album),
    PREVIOUS_IMAGE("Previous image", R.drawable.ic_control_image_previous, R.id.control_previous_image),
    TOGGLE_PAUSE("Pause", R.drawable.ic_control_pause, R.id.control_pause),
    NEXT_IMAGE("Next image", R.drawable.ic_control_image_next, R.id.control_next_image),
    NEXT_ALBUM("Next album", R.drawable.ic_control_album_next, R.id.control_next_album),
    RANDOM_IMAGE("Random image", R.drawable.ic_control_image_random, R.id.control_random_image),
    RANDOM_ALBUM("Random album", R.drawable.ic_control_album_random, R.id.control_random_album),
    ;

    fun icon(paused: Boolean): Int = if (this == TOGGLE_PAUSE && paused) R.drawable.ic_control_play else icon

    fun label(paused: Boolean): String = if (this == TOGGLE_PAUSE && paused) "Play" else label
}
