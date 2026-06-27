package dev.readflow.core.model

/** TXT 正文编码覆盖项。AUTO=沿用自动检测；其余强制指定。 */
enum class TxtEncoding(val charsetName: String?) {
    AUTO(null),
    UTF_8("UTF-8"),
    GBK("GBK"),
    GB18030("GB18030"),
    BIG5("Big5"),
    SHIFT_JIS("Shift_JIS"),
}
