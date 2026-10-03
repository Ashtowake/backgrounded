package dev.backgrounded.domain.model

enum class DisplayTarget {
    COVER,
    INNER,
    ;

    companion object {
        fun from(value: String?): DisplayTarget = entries.firstOrNull { it.name == value } ?: INNER
    }
}
