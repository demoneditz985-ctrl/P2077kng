package com.shadow.injector

import java.util.concurrent.TimeUnit

/**
 * Tiny synchronous shell wrapper. Every call spawns its own process, which is slow but
 * bullet-proof across su implementations (Magisk, KernelSU, APatch, LineageOS su, ...).
 */
object RootShell {

    data class Result(
        val exit: Int,
        val out: String,
        val err: String,
        val timedOut: Boolean = false
    ) {
        val combined: String get() = out + err
        val ok: Boolean get() = exit == 0 && !timedOut
    }

    /** Run [command] through su. */
    fun root(command: String, timeoutSec: Int = 25): Result {
        var r = exec(arrayOf("su", "-c", command), timeoutSec)
        // 127 / "not found" => this su build does not support -c, try the other common form.
        if (r.exit == 127 || (r.exit != 0 && r.err.contains("not found", ignoreCase = true))) {
            r = exec(arrayOf("su", "0", "sh", "-c", command), timeoutSec)
        }
        return r
    }

    /** Run [command] as the app's own user (used for non-root reads). */
    fun sh(command: String, timeoutSec: Int = 15): Result =
        exec(arrayOf("sh", "-c", command), timeoutSec)

    /** True when we can actually get uid 0. */
    fun hasRoot(): Boolean {
        val r = root("id -u", timeoutSec = 12)
        return r.exit == 0 && r.out.trim().split("\n").lastOrNull()?.trim() == "0"
    }

    /** "MAGISK", "KSU", "APATCH" or null. */
    fun rootImpl(): String? {
        val r = root(
            "if command -v magisk >/dev/null 2>&1; then echo MAGISK;" +
                "elif command -v ksud >/dev/null 2>&1; then echo KSU;" +
                "elif command -v apd >/dev/null 2>&1; then echo APATCH;" +
                "elif [ -d /data/adb/magisk ]; then echo MAGISK; fi",
            timeoutSec = 12
        )
        return r.out.trim().split("\n").lastOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun exec(command: Array<String>, timeoutSec: Int): Result {
        return try {
            val proc = Runtime.getRuntime().exec(command)
            val out = StringBuilder()
            val err = StringBuilder()

            val t1 = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
                }
            }
            val t2 = Thread {
                runCatching {
                    proc.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') }
                }
            }
            t1.start()
            t2.start()

            val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) runCatching { proc.destroy() }
            t1.join(3_000)
            t2.join(3_000)

            Result(
                exit = if (finished) proc.exitValue() else -1,
                out = out.toString(),
                err = err.toString(),
                timedOut = !finished
            )
        } catch (e: Exception) {
            Result(-1, "", e.message ?: e.toString())
        }
    }
}
