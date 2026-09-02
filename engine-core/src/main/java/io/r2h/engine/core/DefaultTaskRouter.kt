package io.r2h.engine.core

class DefaultTaskRouter : TaskRouter {
    override fun route(
        input: InferenceInput,
        candidateModels: List<ModelDescriptor>,
        registry: RuntimeRegistry,
    ): RoutingDecision {
        if (candidateModels.isEmpty()) return RoutingDecision.Rejected(RoutingDecision.Reason.NO_CANDIDATE_MODELS)

        val required = requiredCapabilities(input.task)
        for (model in candidateModels) {
            val resolution = registry.resolveRuntimes(model, required, ExecutionLocality.LOCAL)
            if (resolution is RuntimeResolution.Resolved) {
                return RoutingDecision.Routed(
                    runtimeKey = resolution.runtime.descriptor.key,
                    modelId = model.id,
                    locality = ExecutionLocality.LOCAL,
                )
            }
        }
        return RoutingDecision.Rejected(RoutingDecision.Reason.NO_COMPATIBLE_RUNTIME)
    }

    private fun requiredCapabilities(task: InferenceInput.Task): Set<ModelCapability> = when (task) {
        InferenceInput.Task.Chat,
        InferenceInput.Task.TextGeneration,
        InferenceInput.Task.Summarization,
        InferenceInput.Task.Rewriting,
        InferenceInput.Task.Extraction,
        InferenceInput.Task.QuestionAnswering -> setOf(ModelCapability.Input.Text, ModelCapability.Output.Text)
        InferenceInput.Task.Embedding -> setOf(ModelCapability.Input.Text, ModelCapability.Output.Embedding)
        InferenceInput.Task.Reranking -> setOf(ModelCapability.Input.Text, ModelCapability.Output.Labels)
        InferenceInput.Task.Classification -> setOf(ModelCapability.Output.Labels)
        InferenceInput.Task.Detection,
        InferenceInput.Task.Segmentation,
        InferenceInput.Task.Ocr,
        InferenceInput.Task.ImageTextMultimodal,
        InferenceInput.Task.ImageUnderstanding -> setOf(ModelCapability.Input.Image, ModelCapability.Output.Text)
        InferenceInput.Task.VideoFullAnalysis -> setOf(ModelCapability.Input.Video, ModelCapability.Output.Text)
        InferenceInput.Task.ImageGeneration -> setOf(ModelCapability.Input.Text, ModelCapability.Output.Image)
        InferenceInput.Task.AudioAnalysis,
        InferenceInput.Task.AudioTagging,
        InferenceInput.Task.SpeechToText -> setOf(ModelCapability.Input.Audio, ModelCapability.Output.Text)
        InferenceInput.Task.TextToSpeech -> setOf(ModelCapability.Input.Text, ModelCapability.Output.Audio)
    } + ModelCapability.Execution.Local
}
