package app.mapstotom

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mapstotom.ui.Status
import app.mapstotom.ui.StatusNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something read from the TomTom: still reading, read, or the read failed (with why). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * Read off the UI thread, again whenever [keys] change. While a re-read runs the last answer stays,
 * so the screen doesn't flash back to loading after every change.
 */
@Composable
fun <T> produceLoad(vararg keys: Any?, read: suspend () -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, *keys) {
        value = withContext(Dispatchers.IO) {
            runCatching { read() }.fold({ Load.Ready(it) }, { Load.Failed(it.message ?: it::class.simpleName ?: "unknown error") })
        }
    }

/** "Couldn't read …" with the reason and a way to try again. */
@Composable
fun LoadFailed(state: AppState, what: String, f: Load.Failed, modifier: Modifier = Modifier) =
    StatusNote(Status.BAD, "Couldn't read $what from the TomTom: ${f.message}", modifier) {
        TextButton(onClick = { state.refresh() }, enabled = state.busy == null, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Read again") }
    }
