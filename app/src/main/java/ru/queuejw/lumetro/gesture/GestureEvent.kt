package ru.queuejw.lumetro.gesture

import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.inspire.ManagedBitmap
import ru.queuejw.lumetro.search.InjectionTask

data class InspireEvent(
    val rect: Rect? = null,
    val reSelect: Boolean? = null,
    val image: ManagedBitmap? = null,
    val text: String? = null,
    val skipAutoPin: Boolean = false,
    val fullscreen: Boolean = false,
    val forceImageTextSelection: Boolean = false,
    val side: String? = null,
)

data class SearchEvent(val task: InjectionTask)

object EventBusManager {
    val inspireEventBus = MutableSharedFlow<InspireEvent>()
    val searchEventBus = MutableSharedFlow<SearchEvent>()

    suspend fun produceInspireEvent(event: InspireEvent) = withContext(Dispatchers.IO) {
        inspireEventBus.emit(event)
    }

    suspend fun produceSearchEvent(event: SearchEvent) = withContext(Dispatchers.IO) {
        searchEventBus.emit(event)
    }
}
