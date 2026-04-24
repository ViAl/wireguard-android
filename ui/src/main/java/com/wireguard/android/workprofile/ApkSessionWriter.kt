package com.wireguard.android.workprofile

import android.content.pm.PackageInstaller
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

class ApkSessionWriter {
    fun writeApks(session: PackageInstaller.Session, fds: List<ParcelFileDescriptor>) {
        fds.forEachIndexed { index, fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                session.openWrite("apk_$index", 0, -1).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
