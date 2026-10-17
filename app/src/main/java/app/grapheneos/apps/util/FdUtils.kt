package app.grapheneos.apps.util

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.OsConstants.O_EXCL
import android.system.OsConstants.O_RDWR
import android.system.OsConstants.S_IRUSR
import android.system.OsConstants.S_IWUSR
import android.util.Log
import app.grapheneos.apps.core.filesDir
import java.io.FileDescriptor

private const val TAG = "FdUtils"

// O_TMPFILE flag is not a part of Android SDK, though it is part of Android NDK
private val O_TMPFILE = when (Build.SUPPORTED_64_BIT_ABIS[0]) {
    "arm64-v8a" -> 0x404000
    "x86_64" -> 0x410000
    else -> throw IllegalStateException("unsupported ABI")
}

// Returns a file descriptor that is backed by device storage, but not by any file.
// Its contents will be discarded automatically when it's closed, or when app process dies.
// This allows to avoid creating temporary files that would needlessly take up storage space if
// app process is killed, OS crashes etc., and removes the problem of allocating unique temp file names
@Throws(ErrnoException::class)
fun makeTemporaryFileDescriptor(): ScopedFileDescriptor {
    val flags = O_RDWR or O_TMPFILE or O_EXCL
    val mode = S_IRUSR or S_IWUSR
    return ScopedFileDescriptor(Os.open(filesDir.path, flags, mode))
}

@Throws(ErrnoException::class)
fun sendfile(outFd: FileDescriptor, inFd: FileDescriptor, count: Long) {
    var written = 0L
    while (written != count) {
        val chunkLen = count - written
        val ret = Os.sendfile(outFd, inFd, null, chunkLen)
        check(ret > 0 && ret <= chunkLen)
        written += ret
    }
}

fun lseekToStart(fd: FileDescriptor) {
    check(Os.lseek(fd, 0L, OsConstants.SEEK_SET) == 0L)
}

class ScopedFileDescriptor(val v: FileDescriptor) : AutoCloseable {
    // Needed for accessing the int value of fd.
    // Strangely, access to int value of FileDescriptor is restricted behind hidden and discouraged
    // FileDescriptor.getInt$(), but is part of public API of ParcelFileDescriptor
    fun dupToPfd() = ParcelFileDescriptor.dup(v)

    override fun close() {
        try {
            Os.close(v)
        } catch (e: ErrnoException) {
            Log.d(TAG, "", e)
        }
    }
}
