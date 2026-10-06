package dev.backgrounded.domain.usecase

import dev.backgrounded.domain.rotation.RotationCoordinator
import dev.backgrounded.schedule.ChangeScheduler
import dev.backgrounded.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TogglePause
    @Inject
    constructor(
        private val coordinator: RotationCoordinator,
        private val changeScheduler: ChangeScheduler,
        private val widgetUpdater: WidgetUpdater,
    ) {
        suspend operator fun invoke(): Boolean {
            val newValue = coordinator.pause()
            changeScheduler.rearm()
            widgetUpdater.refreshPlayback()
            return newValue
        }
    }
