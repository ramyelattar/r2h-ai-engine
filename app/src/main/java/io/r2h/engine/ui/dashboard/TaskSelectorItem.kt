package io.r2h.engine.ui.dashboard

data class TaskSelectorItem(
    val id: String,
    val label: String,
    val capabilityState: String,
    val enabled: Boolean = false,
)
