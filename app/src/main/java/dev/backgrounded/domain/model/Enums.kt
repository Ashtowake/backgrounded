package dev.backgrounded.domain.model

enum class FitMode {
    FILL,
    FIT,
    BACKGROUND_FILL,
    STRETCH,
    ;

    companion object {
        fun from(value: String?): FitMode =
            if (value == BACKGROUND_FILL.name) FIT else entries.firstOrNull { it.name == value } ?: FILL
    }
}

enum class BackdropType {
    NONE,
    BLUR,
    COLOR,
    ;

    companion object {
        fun from(value: String?): BackdropType = entries.firstOrNull { it.name == value } ?: NONE
    }
}

enum class SourceType {
    IMPORT,
    SAF_LINK,
    ENCRYPTED_IMPORT,
    ;

    companion object {
        fun from(value: String?): SourceType = entries.firstOrNull { it.name == value } ?: IMPORT
    }
}

enum class Trigger {
    MANUAL,
    WIDGET,
    TILE,
    TIMER,
    UNLOCK,
    GESTURE,
    ALBUM_SWITCH,
    EXTERNAL,
}

enum class RotationOrder {
    SEQUENTIAL,
    SHUFFLE,
    ;

    companion object {
        fun from(value: String?): RotationOrder = entries.firstOrNull { it.name == value } ?: SEQUENTIAL
    }
}

enum class ScheduleType {
    NONE,
    INTERVAL,
    FIXED_TIMES,
    ;

    companion object {
        fun from(value: String?): ScheduleType = entries.firstOrNull { it.name == value } ?: NONE
    }
}

enum class SlideMode {
    OFF,
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    DIAGONAL_UP_RIGHT,
    ZOOM_IN,
    ;

    companion object {
        fun from(value: String?): SlideMode = entries.firstOrNull { it.name == value } ?: OFF
    }
}

enum class DoubleTapMode {
    BACKGROUND,
    ANYWHERE,
    ;

    companion object {
        fun from(value: String?): DoubleTapMode = entries.firstOrNull { it.name == value } ?: BACKGROUND
    }
}

enum class GestureAction {
    NEXT,
    PREVIOUS,
    NEXT_ALBUM,
    TOGGLE_PAUSE,
    OPEN_APP,
    ;

    companion object {
        fun from(value: String?): GestureAction = entries.firstOrNull { it.name == value } ?: NEXT
    }
}
