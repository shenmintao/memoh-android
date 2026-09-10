package icu.minq.memoh.model

/** Empty error fields are normal in terminal run patches; only failures get a fallback. */
fun RuntimeRun.visibleError(): String? {
    val detail = error?.trim()?.takeIf { it.isNotEmpty() }
    if (detail == "session_runtime.history_inconsistent" || error_code == "session_runtime.history_inconsistent") {
        return "会话历史保存或同步失败，请刷新并核对最后一条回复后重试"
    }
    return detail ?: when (status) {
        "errored" -> error_code?.trim()?.takeIf { it.isNotEmpty() }?.let { "回复失败（$it）" } ?: "回复失败，请稍后重试"
        "lost" -> "任务连接已丢失，请核对会话中的执行结果"
        else -> null
    }
}
