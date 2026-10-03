package dev.backgrounded.domain.model

enum class FitMode {
    FILL,
    FIT,
    BACKGROUND_FILL,
    STRETCH,
    ;

    companion object {
        fun from(value: String?): FitMode = entries.firstOrNull { it.name == value } ?: FILL
    }
}

enum class BackdropType {
    BLUR,
    COLOR,
    ;

    companion object {
        fun from(value: String?): BackdropType = entries.firstOrNull { it.name == value } ?: BLUR
    }
}

enum class SourceType {
    IMPORT,
    SAF_LINK,
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
