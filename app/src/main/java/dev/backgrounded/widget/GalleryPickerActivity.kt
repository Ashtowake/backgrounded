package dev.backgrounded.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import dagger.hilt.android.AndroidEntryPoint
import dev.backgrounded.core.security.HiddenSwitchAuthActivity
import dev.backgrounded.core.security.HiddenSwitchOperation
import dev.backgrounded.domain.model.Trigger

@AndroidEntryPoint
class GalleryPickerActivity : ComponentActivity() {
    private val viewModel: GalleryPickerViewModel by viewModels()
    private var pendingPairId: Long? = null
    private val authentication =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.revealHidden()
                pendingPairId?.let { id -> viewModel.selectPair(id, {}, ::finish) }
            }
            pendingPairId = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GalleryPickerScreen(viewModel, ::finish, ::authenticate) { id ->
                    viewModel.selectPair(id, {
                        pendingPairId = id
                        authenticate()
                    }, ::finish)
                }
            }
        }
    }

    override fun onStop() {
        viewModel.concealHidden()
        super.onStop()
    }

    private fun authenticate() {
        authentication.launch(
            HiddenSwitchAuthActivity.intent(this, Trigger.WIDGET, operation = HiddenSwitchOperation.REVEAL),
        )
    }
}
