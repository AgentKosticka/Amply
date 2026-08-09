package com.agentkosticka.amply.settings.model

private val knownProfileSuffix = Regex(
    pattern = """\s*(?:\((?:work|personal|profile(?:\s+\d+)?|clone|dual(?:\s+app)?|secure\s+folder)\)|\[(?:work|personal|profile(?:\s+\d+)?|clone|dual(?:\s+app)?|secure\s+folder)\])\s*$""",
    option = RegexOption.IGNORE_CASE
)
private val anyParenthesizedSuffix = Regex("""\s*(?:\([^()]+\)|\[[^\[\]]+\])\s*$""")

internal fun appDisplayName(
    appName: String,
    userId: Int,
    personalUserId: Int,
    hideProfileIdentity: Boolean
): String {
    if (!hideProfileIdentity) return appName
    val suffix = if (userId == personalUserId) knownProfileSuffix else anyParenthesizedSuffix
    return appName.replace(suffix, "").trim().ifEmpty { appName }
}

internal fun appProfileFallbackLabel(
    appName: String,
    userId: Int,
    personalUserId: Int,
    hideProfileIdentity: Boolean
): String? {
    if (hideProfileIdentity || anyParenthesizedSuffix.containsMatchIn(appName)) return null
    return if (userId == personalUserId) "(Personal)" else "(Work)"
}
