package com.seamlesspassage.spa.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp

@Composable
fun LongHoldHotspot(
    modifier: Modifier = Modifier,
    holdMillis: Long = 6000,
    onTriggered: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(120.dp)
            .pointerInput(holdMillis) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        try {
                            withTimeout(holdMillis) {
                                // Wait until finger goes up before timeout; if it goes up -> no trigger
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) break
                                    if (change.changedToUp()) break
                                } while (true)
                            }
                            // released before timeout, ignore
                        } catch (_: TimeoutCancellationException) {
                            // Still holding after timeout -> trigger
                            onTriggered()
                        }
                    }
                }
            }
    )
}
