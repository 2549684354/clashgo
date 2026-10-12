package com.stelliberty.android.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

// 写侧走常驻 BufferedWriter：内核 info 级日志一秒能来好几行，原先每行都 open-append-close
// 加一次裁剪检查，代理跑一天就是几十万次开关文件的 flash I/O。现在攒够 8KB 或 3 秒才落盘
// 一次，裁剪检查也挪到落盘时做。崩溃最多丢 3 秒日志，可接受；读侧（导出/回读）必先 flush。
class FileLogStore(
    private val logFile: File,
    private val maxSizeBytes: Long = MAX_LOG_SIZE_BYTES,
) {
    private val lock = Any()
    private var writer: BufferedOutputStream? = null
    private var pendingBytes = 0
    private var firstPendingAt = 0L

    fun append(line: String) = synchronized(lock) {
        val out = ensureWriter()
        // 同一份编码既用于写出也用于计数：只为取长度再编码一次，会在热路径上多分配一份数组。
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        out.write(bytes)
        out.write('\n'.code)
        pendingBytes += bytes.size + 1
        if (firstPendingAt == 0L) firstPendingAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (pendingBytes >= FLUSH_PENDING_BYTES || now - firstPendingAt >= FLUSH_INTERVAL_MS) {
            flushLocked()
        }
    }

    fun readTail(maxBytes: Long): String = synchronized(lock) {
        if (!logFile.exists()) return@synchronized ""
        flushLocked()
        readTailBytes(maxBytes).toString(StandardCharsets.UTF_8)
    }

    fun readBytes(): ByteArray = synchronized(lock) {
        if (!logFile.exists()) return@synchronized ByteArray(0)
        flushLocked()
        logFile.readBytes()
    }

    fun clear() = synchronized(lock) {
        closeWriterLocked()
        if (logFile.exists()) logFile.writeText("")
    }

    private fun ensureWriter(): BufferedOutputStream {
        writer?.let { return it }
        logFile.parentFile?.mkdirs()
        val raw = FileOutputStream(logFile, /* append = */ true)
        return BufferedOutputStream(raw, WRITE_BUFFER_BYTES).also { writer = it }
    }

    // 调用方必须已持有 lock。
    private fun flushLocked() {
        val out = writer ?: return
        runCatching { out.flush() }.onFailure {
            AppLogger.warn("FileLogStore", "Flush log file failed", it)
        }
        pendingBytes = 0
        firstPendingAt = 0
        trimToMaxSizeLocked()
    }

    private fun closeWriterLocked() {
        writer?.let { runCatching { it.close() }.onFailure {
            AppLogger.warn("FileLogStore", "Close log file failed", it)
        } }
        writer = null
        pendingBytes = 0
        firstPendingAt = 0
    }

    private fun trimToMaxSizeLocked() {
        if (logFile.length() <= maxSizeBytes) return
        closeWriterLocked()
        logFile.writeBytes(readTailBytes(maxSizeBytes / 2))
    }

    // 从尾部回读固定字节窗口，不把整个文件读进堆。窗口起点会切在半行中间，故丢掉首行残段；
    // UTF-8 多字节序列里不会出现 0x0A，按字节找换行是安全的。
    private fun readTailBytes(maxBytes: Long): ByteArray {
        if (maxBytes <= 0L) return ByteArray(0)
        val length = logFile.length()
        val start = (length - maxBytes).coerceAtLeast(0L)
        val bytes = ByteArray((length - start).toInt())
        RandomAccessFile(logFile, "r").use { input ->
            input.seek(start)
            input.readFully(bytes)
        }
        if (start == 0L) return bytes
        val firstBreak = bytes.indexOf('\n'.code.toByte())
        return if (firstBreak >= 0) bytes.copyOfRange(firstBreak + 1, bytes.size) else bytes
    }

    companion object {
        const val MAX_LOG_SIZE_BYTES = 5L * 1024L * 1024L
        private const val FLUSH_PENDING_BYTES = 8L * 1024L
        private const val FLUSH_INTERVAL_MS = 3_000L
        private const val WRITE_BUFFER_BYTES = 16 * 1024
    }
}
