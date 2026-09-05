package icu.minq.memoh.service

import icu.minq.memoh.data.PendingPhase
import icu.minq.memoh.model.RuntimeRun

/** No server-supplied text ever enters a system notification. */
object ReplyNotificationPolicy {
    fun result(phase: PendingPhase): String = when (phase) {
        PendingPhase.COMPLETED -> "Memoh 回复已完成"
        PendingPhase.FAILED -> "Memoh 回复出错或已停止，请打开应用查看"
        else -> "后台监听已暂停，执行结果尚未确认，请打开应用查看"
    }
    fun decisionIds(run: RuntimeRun): Set<String> = run.messages.mapNotNull { block ->
        block.approval?.takeIf { it.status == "pending" }?.let { "approval:${it.approvalId}" }
            ?: block.userInput?.takeIf { it.status == "pending" }?.let { "input:${it.userInputId}" }
    }.toSet()
}
