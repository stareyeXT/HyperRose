package com.dohex.hyperrose.ipc

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object BroadcastSenderValidator {
    fun isAllowed(
        packageManager: PackageManager,
        sentFromUid: Int,
        allowedPackages: Set<String>,
    ): Boolean = runCatching {
        isAllowedPackages(packageManager.getPackagesForUid(sentFromUid), allowedPackages)
    }.getOrDefault(false)

    internal fun isAllowedPackages(
        packages: Array<out String>?,
        allowedPackages: Set<String>,
    ): Boolean = packages?.any(allowedPackages::contains) == true
}

fun Context.sendHyperRoseBroadcast(intent: Intent) {
    val options =
        BroadcastOptions.makeBasic()
            .setShareIdentityEnabled(true)
            .toBundle()
    sendBroadcast(intent, null, options)
}
