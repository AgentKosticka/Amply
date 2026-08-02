package com.agentkosticka.amply.service

import android.content.Intent
import android.content.pm.PackageManager
import android.service.autofill.AutofillService
import android.view.inputmethod.InputMethod

internal data class ForegroundWindowCandidate(
    val packageName: String?,
    val className: String?
)

/** Filters windows that can temporarily sit above the actual foreground app. */
internal class ForegroundAppResolver(
    private val ignoredPackages: Set<String>,
    private val ignoredPackagePrefixes: Set<String> = setOf(
        "com.android.systemui",
        "com.nothing.systemui"
    )
) {
    fun resolve(candidate: ForegroundWindowCandidate): String? {
        val packageName = candidate.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (packageName in ignoredPackages || ignoredPackagePrefixes.any(packageName::startsWith)) {
            return null
        }
        val className = candidate.className.orEmpty().lowercase()
        if (TRANSIENT_CLASS_HINTS.any { it in className }) return null
        return packageName
    }

    companion object {
        private val TRANSIENT_CLASS_HINTS = setOf(
            "softinputwindow",
            "inputmethod",
            "permissiondialog",
            "grantpermissions",
            "autofill",
            "resolveractivity",
            "chooseractivity"
        )

        fun fromPackageManager(packageManager: PackageManager, ownPackage: String): ForegroundAppResolver {
            val ignored = linkedSetOf(ownPackage, "android")
            ignored += "com.android.permissioncontroller"
            ignored += "com.google.android.permissioncontroller"
            runCatching {
                packageManager.queryIntentActivities(
                    Intent("android.intent.action.MANAGE_PERMISSIONS"),
                    PackageManager.MATCH_ALL
                )
            }.getOrDefault(emptyList()).mapTo(ignored) { it.activityInfo.packageName }
            listOf(InputMethod.SERVICE_INTERFACE, AutofillService.SERVICE_INTERFACE).forEach { action ->
                runCatching {
                    packageManager.queryIntentServices(Intent(action), PackageManager.MATCH_ALL)
                }.getOrDefault(emptyList()).mapTo(ignored) { it.serviceInfo.packageName }
            }
            return ForegroundAppResolver(ignored)
        }
    }
}
