package dev.backgrounded.domain.usecase

import dev.backgrounded.data.datastore.SettingsStore
import dev.backgrounded.schedule.ChangeScheduler
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TogglePause
    @Inject
    constructor(
        private val settingsStore: SettingsStore,
        private val changeScheduler: ChangeScheduler,
    ) {
        suspend operator fun invoke(): Boolean {
            val paused = settingsStore.settings.first().rotationPaused
            val newValue = !paused
            settingsStore.setRotationPaused(newValue)
            changeScheduler.rearm()
            return newValue
        }
    }
