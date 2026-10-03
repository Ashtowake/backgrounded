package dev.backgrounded.tiles

import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.core.di.ApplicationScope
import dev.backgrounded.domain.model.Trigger
import dev.backgrounded.domain.usecase.ApplyNextBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WallpaperTileService : TileService() {
    @Inject
    lateinit var applyNextBackground: ApplyNextBackground

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onClick() {
        super.onClick()
        applicationScope.launch {
            applyNextBackground(Trigger.TILE)
        }
    }
}
