package com.deepseek.coder.core

import timber.log.Timber

/**
 * Thin wrapper around Timber so domain/data code does not directly depend on the logging library.
 * Makes unit testing easier (replace AppLogger impl with no-op).
 */
object AppLogger {
    fun d(message: String, vararg args: Any?) = Timber.d(message, *args)
    fun i(message: String, vararg args: Any?) = Timber.i(message, *args)
    fun w(t: Throwable? = null, message: String = "", vararg args: Any?) =
        if (t == null) Timber.w(message, *args) else Timber.w(t, message, *args)
    fun e(t: Throwable? = null, message: String = "", vararg args: Any?) =
        if (t == null) Timber.e(message, *args) else Timber.e(t, message, *args)
}
