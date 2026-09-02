package io.r2h.engine.ui.dashboard

class EngineDashboardUiController {
    fun buildUnavailableState(): TaskAwareUiState = TaskAwareUiState(
        tasks = listOf(
            TaskSelectorItem("text-generation", "Text generation", "MODEL_MISSING"),
            TaskSelectorItem("image-understanding", "Image understanding", "RUNTIME_MISSING"),
            TaskSelectorItem("speech-to-text", "Speech to text", "RUNTIME_MISSING"),
            TaskSelectorItem("multimodal", "Multimodal", "MODEL_MISSING"),
        ),
    )
}
