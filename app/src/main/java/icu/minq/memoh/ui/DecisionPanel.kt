package icu.minq.memoh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import icu.minq.memoh.data.UiState
import icu.minq.memoh.model.*
import kotlinx.serialization.json.*

@Composable internal fun DecisionPanel(state: UiState, actions: UiActions, blocks: List<MessageBlock>) {
    val block = blocks.first()
    val input = block.userInput?.takeIf { it.canAnswer() }
    val id = input?.userInputId ?: block.approval!!.approvalId
    val enabled = !state.loading && state.connected && state.connectionFailure == null && state.pendingControls.isEmpty()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(id) { keyboard?.hide() }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        key(state.session?.id, state.runtime.run?.run_id, id) {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
                .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (input != null) "需要你的回答" else "需要你的批准", style = MaterialTheme.typography.titleSmall)
                if (blocks.size > 1) Text("还有 ${blocks.size - 1} 个请求待处理", style = MaterialTheme.typography.bodySmall)
                if (!state.connected || state.connectionFailure != null) Text("连接恢复后即可处理，尚未自动提交", style = MaterialTheme.typography.bodySmall)
                if (state.pendingControls.isNotEmpty()) Text("正在确认你的选择…", style = MaterialTheme.typography.bodySmall)
                if (input != null) UserInputForm(input, enabled, actions)
                else ApprovalForm(block, enabled, actions)
            }
        }
    }
}

@Composable private fun ApprovalForm(block: MessageBlock, enabled: Boolean, actions: UiActions) {
    val approval = block.approval!!
    var rejecting by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var rejectOption by remember { mutableStateOf<String?>(null) }
    val data = block.input as? JsonObject
    fun field(name: String) = (data?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val title = field("title").ifBlank { block.name.ifBlank { "工具调用" } }
    val preview = field("command").ifBlank { field("request") }.ifBlank { block.input?.toString().orEmpty() }
    Text(title, style = MaterialTheme.typography.bodyMedium)
    if (preview.isNotBlank()) Text(preview, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
    if (rejecting) {
        OutlinedTextField(reason, { reason = it }, enabled = enabled, label = { Text("拒绝原因（可选）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ rejecting = false; reason = "" }, enabled = enabled) { Text("返回") }
            Button({ actions.decide(approval.approvalId, false, rejectOption, reason) }, enabled = enabled) { Text("确认拒绝") }
        }
    } else {
        val options = approval.options.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (options.isEmpty()) Button({ actions.decide(approval.approvalId, true, null, "") }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("批准") }
        options.forEach { option ->
            OutlinedButton({
                if (option.approves()) actions.decide(approval.approvalId, true, option.id, "")
                else { rejectOption = option.id; rejecting = true }
            }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(option.actionLabel()) }
        }
        if (options.none { !it.approves() }) OutlinedButton({ rejectOption = null; rejecting = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("拒绝") }
    }
}

private data class AnswerDraft(val selected: List<String> = emptyList(), val text: String = "")

@Composable private fun UserInputForm(input: UserInput, enabled: Boolean, actions: UiActions) {
    var drafts by remember { mutableStateOf<Map<String, AnswerDraft>>(emptyMap()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val answers = input.questions.map { question ->
        val draft = drafts[question.id] ?: AnswerDraft()
        val text = draft.text.trim()
        when {
            text.isBlank() && draft.selected.isEmpty() && !question.required -> UserAnswer(question.id, skipped = true)
            question.kind == "text" -> UserAnswer(question.id, text = text)
            else -> UserAnswer(question.id, optionIds = draft.selected, customText = text)
        }
    }
    input.questions.forEach { question -> key(question.id) {
        val draft = drafts[question.id] ?: AnswerDraft()
        fun update(value: AnswerDraft) { drafts = drafts + (question.id to value) }
        Text(question.text + if (!question.required) "（可跳过）" else "", style = MaterialTheme.typography.bodyMedium)
        if (question.kind in setOf("single_select", "multi_select")) {
            question.options.filter { it.id.isNotBlank() }.distinctBy { it.id }.forEach { option ->
                val selected = option.id in draft.selected
                Row(Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = if (question.kind == "multi_select") Role.Checkbox else Role.RadioButton) {
                    val next = if (selected) draft.selected - option.id else if (question.kind == "single_select") listOf(option.id) else draft.selected + option.id
                    update(draft.copy(selected = next, text = if (next.isNotEmpty() && (question.kind == "single_select" || question.customExclusive)) "" else draft.text))
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (question.kind == "multi_select") Checkbox(selected, null, enabled = enabled) else RadioButton(selected, null, enabled = enabled)
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (question.kind == "text" || question.allowCustom) OutlinedTextField(draft.text, { text ->
            update(draft.copy(text = text, selected = if (text.isNotBlank() && (question.kind == "single_select" || question.customExclusive)) emptyList() else draft.selected))
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "回答：${question.text}" },
            label = { Text(if (question.kind == "text") "你的回答" else "其他回答") },
            placeholder = { Text(question.placeholder) }, maxLines = 4)
        if (question.kind !in setOf("text", "single_select", "multi_select")) Text("此问题格式暂不支持，可取消请求", style = MaterialTheme.typography.bodySmall)
    } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ keyboard?.hide(); actions.answer(input.userInputId, emptyList(), true) }, enabled = enabled) { Text("取消回答") }
        Button({ keyboard?.hide(); actions.answer(input.userInputId, answers, false) }, enabled = enabled && input.accepts(answers)) { Text("提交回答") }
    }
}
