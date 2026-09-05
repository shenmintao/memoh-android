package icu.minq.memoh.model

/** Live blocks override the matching active turn in history, including resolved requests. */
fun RuntimeRun?.decisionBlocks(history: List<ChatTurn> = emptyList()): List<MessageBlock> {
    if (this == null || isTerminal()) return emptyList()
    return (history.filter { it.role == "assistant" && it.turnId == turn_id && turn_id.isNotBlank() }.flatMap { it.messages } + messages)
        .associateBy { it.toolCallId.ifBlank { "block:${it.id}" } }.values
        .filter { it.type == "tool" && (it.approval?.canDecide() == true || it.userInput?.canAnswer() == true) }
        .distinctBy { it.userInput?.takeIf { input -> input.canAnswer() }?.let { input -> "input:${input.userInputId}" }
            ?: "approval:${it.approval?.approvalId}" }
}
fun Approval.canDecide() = approvalId.isNotBlank() && status == "pending" && canApprove
fun UserInput.canAnswer() = userInputId.isNotBlank() && status == "pending" && canRespond
fun ApprovalOption.approves() = kind.trim().lowercase() in setOf("allow_once", "allow_always")
fun ApprovalOption.actionLabel(): String {
    val label = when (kind.trim().lowercase()) {
        "allow_once" -> "仅本次允许"; "allow_always" -> "始终允许"
        "reject_once" -> "仅本次拒绝"; "reject_always" -> "始终拒绝"; else -> "拒绝"
    }
    return if (name.isBlank() || name == label) label else "$label · $name"
}

/** Validate against the server's question schema, also for callbacks from stale UI. */
fun UserInput.accepts(answers: List<UserAnswer>): Boolean {
    if (!canAnswer() || questions.isEmpty() || questions.map { it.id }.distinct().size != questions.size ||
        answers.size != questions.size || answers.map { it.questionId }.toSet() != questions.map { it.id }.toSet()) return false
    return questions.all { question ->
        val answer = answers.single { it.questionId == question.id }
        val text = answer.text.trim(); val custom = answer.customText.trim(); val selected = answer.optionIds
        when {
            answer.skipped -> !question.required && text.isEmpty() && custom.isEmpty() && selected.isEmpty()
            question.kind == "text" -> text.isNotEmpty() && custom.isEmpty() && selected.isEmpty()
            question.kind !in setOf("single_select", "multi_select") -> false
            text.isNotEmpty() || selected.distinct().size != selected.size || selected.any { id -> question.options.none { it.id == id } } -> false
            custom.isNotEmpty() && !question.allowCustom -> false
            selected.isEmpty() && custom.isEmpty() -> false
            question.kind == "single_select" -> selected.size + (if (custom.isNotEmpty()) 1 else 0) == 1
            question.customExclusive && custom.isNotEmpty() -> selected.isEmpty()
            else -> true
        }
    }
}

data class DecisionReceipt(val runId: String, val id: String, val kind: String, val status: String, val optionId: String? = null) {
    fun unresolved(run: RuntimeRun?): Boolean = run != null && run.run_id == runId && !run.isTerminal() &&
        (if (kind == "approval") run.messages.firstOrNull { it.approval?.approvalId == id }?.approval?.status
        else run.messages.firstOrNull { it.userInput?.userInputId == id }?.userInput?.status).let { it == null || it == "pending" }
    fun apply(run: RuntimeRun?): RuntimeRun? = if (run == null || run.run_id != runId) run else run.copy(messages = run.messages.map { block ->
        when {
            kind == "approval" && block.approval?.approvalId == id && block.approval.status == "pending" ->
                block.copy(approval = block.approval.copy(status = status, canApprove = false, selectedOptionId = optionId))
            kind == "input" && block.userInput?.userInputId == id && block.userInput.status == "pending" ->
                block.copy(userInput = block.userInput.copy(status = status, canRespond = false))
            else -> block
        }
    })
}
