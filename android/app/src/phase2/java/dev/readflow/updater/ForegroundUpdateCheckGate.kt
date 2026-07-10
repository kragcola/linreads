package dev.readflow.updater

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job

internal class ForegroundUpdateCheckGate(
    private val checkForUpdate: () -> Job,
) {
    private var isStarted = false
    private var checkJob: Job? = null

    fun onEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                if (isStarted) return
                isStarted = true
                checkJob?.cancel()
                checkJob = checkForUpdate()
            }

            Lifecycle.Event.ON_STOP -> {
                isStarted = false
                checkJob?.cancel()
                checkJob = null
            }
            else -> Unit
        }
    }

    fun dispose() {
        isStarted = false
        checkJob?.cancel()
        checkJob = null
    }
}
