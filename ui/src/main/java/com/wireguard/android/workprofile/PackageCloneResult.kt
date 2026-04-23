package com.wireguard.android.workprofile

sealed class PackageCloneResult {
    object SuccessInstalledExisting : PackageCloneResult()
    object SuccessEnabledSystemApp : PackageCloneResult()
    object SuccessInstalledFromApkSession : PackageCloneResult()
    object RedirectedToPlayStore : PackageCloneResult()
    object ErrorNoWorkProfileHelper : PackageCloneResult()
    object ErrorNotProfileOwner : PackageCloneResult()
    object ErrorPackageNotFound : PackageCloneResult()
    object ErrorPlayStoreUnavailable : PackageCloneResult()
    object ErrorInstallSessionFailed : PackageCloneResult()
    object ErrorUnsupportedAndroidVersion : PackageCloneResult()
    object ErrorPermissionDenied : PackageCloneResult()
    data class ErrorUnknown(val message: String) : PackageCloneResult()
}
