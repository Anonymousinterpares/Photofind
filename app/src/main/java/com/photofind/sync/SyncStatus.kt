package com.photofind.sync

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * One line the PC setup reads over USB: stage, photos done, photos in this pass.
 * Writing must not fail the scan; the phone keeps working with the cable unplugged.
 */
object SyncStatus {
    fun write(context: Context, stage: String, done: Int, total: Int) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PhotoFind",
            )
            if (!dir.exists() && !dir.mkdirs()) return
            val dest = File(dir, "status.txt")
            val tmp = File(dir, "status.txt.part")
            tmp.writeText("$stage\t$done\t$total\n")
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
