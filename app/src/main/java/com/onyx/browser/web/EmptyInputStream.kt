package com.onyx.browser.web

import java.io.InputStream

/**
 * High-performance, zero-allocation, thread-safe empty [InputStream] singleton.
 * Returns EOF (-1) immediately without maintaining cursor state or allocating byte arrays.
 */
object EmptyInputStream : InputStream() {
    override fun read(): Int = -1
    override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    override fun available(): Int = 0
    override fun skip(n: Long): Long = 0L
}
