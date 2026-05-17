package io.kognis.tactical.core.llm

enum class ChatTemplate { GEMMA }

fun detectTemplate(modelName: String): ChatTemplate = ChatTemplate.GEMMA
