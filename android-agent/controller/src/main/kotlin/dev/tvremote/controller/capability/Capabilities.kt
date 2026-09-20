package dev.tvremote.controller.capability

/**
 * 电视端上报的按键支持状态（对齐 agent `KeyCapability`）。
 *
 * 语义（规范与任务要求）：
 * - SUPPORTED：允许操作。
 * - BEST_EFFORT：允许尝试，但需要标明兼容提示（可能 EXECUTION_FAILED）。
 * - PERMISSION_REQUIRED：禁用并展示电视端设置指引。
 * - UNSUPPORTED：禁用。
 * - UNVERIFIED：不得显示为已验证支持（禁用）。
 */
enum class KeySupport {
    SUPPORTED,
    BEST_EFFORT,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    UNVERIFIED;

    /** 是否允许下发该按键。 */
    val canAttempt: Boolean get() = this == SUPPORTED || this == BEST_EFFORT

    companion object {
        fun parse(raw: String?): KeySupport = when (raw) {
            "SUPPORTED" -> SUPPORTED
            "BEST_EFFORT" -> BEST_EFFORT
            "PERMISSION_REQUIRED" -> PERMISSION_REQUIRED
            "UNVERIFIED" -> UNVERIFIED
            "UNSUPPORTED" -> UNSUPPORTED
            // 缺省或未知：按不支持处理，避免“点了没反应”的静默成功
            else -> UNSUPPORTED
        }
    }
}

/** 电视端上报的文字输入能力（对齐 agent `textInput` 字段）。 */
enum class TextSupport {
    SUPPORTED,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    UNVERIFIED;

    companion object {
        fun parse(raw: String?): TextSupport = when (raw) {
            "SUPPORTED" -> SUPPORTED
            "PERMISSION_REQUIRED" -> PERMISSION_REQUIRED
            "UNVERIFIED" -> UNVERIFIED
            "UNSUPPORTED" -> UNSUPPORTED
            else -> UNVERIFIED
        }
    }
}
