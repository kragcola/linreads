package dev.readflow.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/** Receives the "install update" tap, downloads APK, launches system installer. */
class UpdateInstallReceiver : BroadcastReceiver() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val apkUrl = intent.getStringExtra("apk_url") ?: return
        val tagName = intent.getStringExtra("tag_name") ?: "dev-latest"
        val checker = GitHubUpdateChecker(
            repoSlug = dev.readflow.BuildConfig.GITHUB_REPO,
            currentTag = dev.readflow.BuildConfig.BUILD_TAG,
        )
        GlobalScope.launch {
            runCatching {
                val file = checker.download(UpdateInfo(tagName, apkUrl, ""), context)
                checker.install(context, file)
            }
        }
    }
}
