package com.wireguard.android.workprofile

sealed class PackageCloneResult {
    data object SuccessInstalledExisting : PackageCloneResult()
    data object SuccessEnabledSystemApp : PackageCloneResult()
    data object SuccessInstalledFromApkSession : PackageCloneResult()
    data object RedirectedToPlayStore : PackageCloneResult()
    data object ErrorNoWorkProfileHelper : PackageCloneResult()
    data object ErrorNotProfileOwner : PackageCloneResult()
    data object ErrorPackageNotFound : PackageCloneResult()
    data object ErrorPlayStoreUnavailable : PackageCloneResult()
    data object ErrorInstallSessionFailed : PackageCloneResult()
    data object ErrorUnsupportedAndroidVersion : PackageCloneResult()
    data object ErrorPermissionDenied : PackageCloneResult()
    data class ErrorUnknown(val message: String) : PackageCloneResult()
}
