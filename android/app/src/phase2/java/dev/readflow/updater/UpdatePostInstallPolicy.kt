package dev.readflow.updater

internal enum class PostInstallTakeoverAction {
    POST_COMPLETION_NOTIFICATION,
    MOVE_MAIN_TASK_TO_FRONT,
    LAUNCH_MAIN_ACTIVITY,
    REMOVE_INSTALLER_TASK,
}

internal enum class PostInstallTaskRole { MAIN, INSTALLER, OTHER }

internal fun postInstallTaskRole(
    packageName: String,
    rootPackageName: String?,
    rootClassName: String?,
): PostInstallTaskRole {
    if (rootPackageName != packageName) return PostInstallTaskRole.OTHER
    return when (rootClassName) {
        "$packageName.MainActivity" -> PostInstallTaskRole.MAIN
        "$packageName.updater.UpdateApkInstallActivity" -> PostInstallTaskRole.INSTALLER
        else -> PostInstallTaskRole.OTHER
    }
}

internal fun postInstallTakeoverActions(
    savedBuildTag: String?,
    armedBuildTag: String?,
    currentBuildTag: String,
    handledBuildTag: String?,
    hasMainTask: Boolean,
    installerTaskCount: Int,
): List<PostInstallTakeoverAction> {
    if (!isInstalledUpdateBuild(savedBuildTag, currentBuildTag)) return emptyList()
    if (armedBuildTag != currentBuildTag) return emptyList()
    if (handledBuildTag == currentBuildTag) return emptyList()
    return buildList {
        add(PostInstallTakeoverAction.POST_COMPLETION_NOTIFICATION)
        add(
            if (hasMainTask) {
                PostInstallTakeoverAction.MOVE_MAIN_TASK_TO_FRONT
            } else {
                PostInstallTakeoverAction.LAUNCH_MAIN_ACTIVITY
            },
        )
        repeat(installerTaskCount.coerceAtLeast(0)) {
            add(PostInstallTakeoverAction.REMOVE_INSTALLER_TASK)
        }
    }
}

internal fun shouldRemovePostInstallInstallerTask(
    hadMainTask: Boolean,
    completionNotificationPosted: Boolean,
): Boolean = hadMainTask || completionNotificationPosted
