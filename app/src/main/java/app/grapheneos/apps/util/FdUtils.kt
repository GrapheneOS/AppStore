package app.grapheneos.apps.util

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Int64Ref
import android.system.Os
import android.system.OsConstants
import android.system.OsConstants.EINVAL
import android.system.OsConstants.O_CREAT
import android.system.OsConstants.O_RDWR
import android.system.OsConstants.O_TRUNC
import android.system.OsConstants.S_IRUSR
import android.system.OsConstants.S_IWUSR
import android.util.Log
import app.grapheneos.apps.core.fileForTemporaryFileDescriptor
import java.io.File
import java.io.FileDescriptor

private const val TAG = "FdUtils"

// Returns a file descriptor that is backed by device storage, but not by any file.
// Its contents will be discarded automatically when it's closed, or when app process dies.
// This allows to avoid creating temporary files that would needlessly take up storage space if
// app process is killed, OS crashes etc, and removes the problem of allocating unique temp file names
@Throws(ErrnoException::class)
fun makeTemporaryFileDescriptor(): ScopedFileDescriptor {
    val fd: FileDescriptor
    val flags = O_RDWR or O_TRUNC or O_CREAT
    val mode = S_IRUSR or S_IWUSR

    val file: File = fileForTemporaryFileDescriptor
    val path = file.path

    // O_TMPFILE flag is not a part of Android SDK, though it is part of Android NDK. Its value is
    // ABI-dependent. Approximate O_TMPFILE with open() + unlink()
    synchronized(file) {
        fd = Os.open(path, flags, mode)
        // there's no wrapper for unlink()
        if (!file.delete()) {
            Os.close(fd)
            // should never happen
            throw ErrnoException("makeTemporaryFileDescriptor", EINVAL)
        }
    }

    return ScopedFileDescriptor(fd)
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
