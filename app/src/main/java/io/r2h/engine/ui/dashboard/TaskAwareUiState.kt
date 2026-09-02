package io.r2h.engine.ui.dashboard

data class TaskAwareUiState(
    val selectedTaskId: String? = null,
    val tasks: List<TaskSelectorItem> = emptyList(),
    val errorMessage: String? = null,
)
