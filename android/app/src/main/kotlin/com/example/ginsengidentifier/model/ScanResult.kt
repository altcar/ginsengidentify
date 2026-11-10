package com.example.ginsengidentifier.model

/** 一次识别结果，贯穿通知 / 悬浮窗 / WebSocket / 小组件。 */
data class ScanResult(
    val label: String,
    val displayName: String,
    val confidence: Float,
    val runnerUpLabel: String = "",
    val runnerUpConfidence: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    /** 给用户看的一句话说明，例如"画面太暗" */
    val note: String = "",
) {
    val confidencePercent: String get() = "%.0f%%".format(confidence * 100)

    /** 第 4 类：什么都没检测到。 */
    val isOther: Boolean get() = label == LABEL_OTHER

    /** 低于阈值就不要装成"确定"。 */
    val isConfident: Boolean get() = !isOther && confidence >= CONFIDENCE_FLOOR

    fun toJson(): String = buildString {
        append('{')
        append("\"label\":\"").append(label).append("\",")
        append("\"display\":\"").append(displayName).append("\",")
        append("\"detected\":").append(!isOther).append(',')
        append("\"confidence\":").append("%.4f".format(confidence)).append(',')
        append("\"runner_up\":\"").append(runnerUpLabel).append("\",")
        append("\"runner_up_confidence\":").append("%.4f".format(runnerUpConfidence)).append(',')
        append("\"note\":\"").append(note.replace("\"", "'")).append("\",")
        append("\"timestamp\":").append(timestamp)
        append('}')
    }

    companion object {
        const val CONFIDENCE_FLOOR = 0.55f
        const val LABEL_OTHER = "other"
        const val NO_DETECTION = "未检测到相关药材"

        fun none(reason: String): ScanResult =
            ScanResult(LABEL_OTHER, NO_DETECTION, 0f, note = reason)
    }
}

/** 统计数据：连续天数 + 今日次数 + 各类计数，小组件和首页共用。 */
data class ScanStats(
    val streak: Int = 0,
    val todayCount: Int = 0,
    val dailyGoal: Int = 10,
    val totalCount: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
) {
    val goalProgress: Float
        get() = (todayCount.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)

    fun countOf(label: String): Int = counts[label] ?: 0
}
