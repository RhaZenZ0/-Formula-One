package com.rhazenz0.f1info.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives the package installer's progress for a self-update. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
                UpdateManager.onInstallerWaiting()
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.onInstalled()
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateManager.onInstallFailed(null)
            else -> UpdateManager.onInstallFailed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This update is signed with a different key than the installed app. Uninstall F1 Info, then install the new version from the Releases page."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space to install the update."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installing was blocked by the device."
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update could not be installed."
                },
            )
        }
    }

    companion object {
        const val ACTION = "com.rhazenz0.f1info.INSTALL_RESULT"
    }
}
