package icu.minq.memoh.data

import icu.minq.memoh.model.*

fun UiState.pendingDecisions(): List<MessageBlock> = runtime.run.decisionBlocks(history).filter { block ->
    val id = block.userInput?.takeIf { it.canAnswer() }?.let { "input:${it.userInputId}" } ?: "approval:${block.approval?.approvalId}"
    id !in resolvedDecisions
}
