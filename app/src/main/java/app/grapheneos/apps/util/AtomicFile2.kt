package app.grapheneos.apps.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.grapheneos.apps.core.filesDir
import java.io.File

// android.util.AtomicFile has several issues:
//  - ignores errors from fsync(), rename(), close()
//  - doesn't do fsync of the containing directory
//  - doesn't enforce having at most one writer at a time
//  - checks for a legacy file name that was used by its older implementation
class AtomicFile2(name: String) {
    // Context.getFilesDir() is the only reliable place to write files: files from cache dir may
    // disappear mid-write, before rename of tmpPath. Writes to files on shared storage go through
    // the FUSE daemon, which lowers reliability and performance
    private val file = File(filesDir, name)
    private val dirPath: String = filesDir.path
    private val filePath: String = file.path
    private val tmpFilePath: String = filePath + ".tmp"

    fun read(): ByteArray? {
        if (!file.exists()) {
            return null
        }
        return file.readBytes()
    }

    @Throws(ErrnoException::class)
    fun write(bytes: ByteArray) {
        synchronized(file) {
            writeInner(bytes)
        }
    }

    private fun writeInner(bytes: ByteArray) {
        val flags = OsConstants.O_RDWR or OsConstants.O_CREAT or
                // in case there's a leftover file from previous failed write
                OsConstants.O_TRUNC
        val mode = OsConstants.S_IRUSR or OsConstants.S_IWUSR // 0600

        ScopedFileDescriptor(Os.open(tmpFilePath, flags, mode)).use {
            var written: Int = 0
            val len: Int = bytes.size

            while (written != len) {
                val chunkLen: Int = len - written
                val writeRes: Int = Os.write(it.v, bytes, written, chunkLen)
                check(writeRes > 0 && writeRes <= chunkLen)
                written += writeRes
            }

            Os.fsync(it.v)
        }

        Os.rename(tmpFilePath, filePath)

        // fsync the directory to ensure durability part of ACID
        ScopedFileDescriptor(Os.open(dirPath, OsConstants.O_RDONLY, 0)).use { fd ->
            Os.fsync(fd.v)
        }
    }
}
