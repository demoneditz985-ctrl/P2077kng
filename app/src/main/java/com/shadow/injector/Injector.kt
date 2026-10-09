package com.shadow.injector

import android.content.Context

/**
 * Handles everything that happens as root: staging the two libraries where the game can
 * load them, finding the game's PID, and running the injector binary.
 */
object Injector {

    /**
     * Copies libRootMagisk.so (as "shadow-injector") and libmain.so out of the APK's
     * nativeLibraryDir into /data/local/tmp/shadow, marks them executable and relabels them
     * so an untrusted_app process is allowed to dlopen() the payload.
     */
    fun stage(context: Context): StageResult {
        val libDir = context.applicationInfo.nativeLibraryDir
        val injectorSrc = "$libDir/${InjectorConfig.INJECTOR_LIB}"
        val payloadSrc = "$libDir/${InjectorConfig.PAYLOAD_LIB}"

        val dir = InjectorConfig.WORK_DIR
        val bin = InjectorConfig.INJECTOR_BIN
        val payload = InjectorConfig.PAYLOAD_PATH

        // `cat src > dst` runs entirely inside the root shell, so the app never needs
        // read access to /data/app and the destination gets sane permissions.
        val script = listOf(
            "mkdir -p '$dir'",
            "rm -f '$bin' '$payload'",
            "cat '$injectorSrc' > '$bin'",
            "cat '$payloadSrc' > '$payload'",
            "chmod 0755 '$bin' '$payload'",
            "chown root:root '$bin' '$payload' 2>/dev/null || true",
            // Relabel so the game (untrusted_app) may execute / dlopen them.
            "chcon u:object_r:system_file:s0 '$bin' '$payload' 2>/dev/null " +
                "|| chcon u:object_r:app_data_file:s0 '$bin' '$payload' 2>/dev/null || true",
            "echo STAGED"
        ).joinToString(" ; ")

        val r = RootShell.root(script, timeoutSec = 40)

        // Verify through root: /data/local/tmp is not listable by normal apps.
        val check = RootShell.root(
            "[ -f '$bin' ] && [ -f '$payload' ] && [ -s '$bin' ] && [ -s '$payload' ] && echo OK",
            timeoutSec = 15
        )

        return StageResult(
            ok = check.out.contains("OK"),
            log = (r.out + r.err).trim()
        )
    }

    data class StageResult(val ok: Boolean, val log: String)

    /** First PID whose process name matches [processName], or 0. */
    fun pidOf(processName: String): Int {
        // pidof is the fastest; `ps -A` is the fallback for ROMs without toybox pidof.
        val cmd =
            "pidof '$processName' 2>/dev/null || ps -A -o PID,NAME 2>/dev/null | grep -m1 " +
                "'$processName' | awk '{print ${'$'}1}'"
        val r = RootShell.root(cmd, timeoutSec = 12)
        return r.out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.all { c -> c.isDigit() } }
            ?.toIntOrNull() ?: 0
    }

    /** All PIDs matching the process name (games often fork helpers). */
    fun pidsOf(processName: String): List<Int> {
        val r = RootShell.root("pidof '$processName' 2>/dev/null", timeoutSec = 12)
        return r.out.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.all { c -> c.isDigit() } }
            .mapNotNull { it.toIntOrNull() }
    }

    /** Builds the concrete injector commands for a PID, one per argument template. */
    fun commandCandidates(pid: Int): List<String> =
        InjectorConfig.ARG_TEMPLATES.map {
            it.replace("{pid}", pid.toString())
                .replace("{lib}", InjectorConfig.PAYLOAD_PATH)
        }

    /** Heuristic: the binary rejected our arguments rather than failing at runtime. */
    fun looksLikeUsageError(output: String): Boolean {
        val t = output.lowercase()
        return t.contains("usage:") ||
            t.contains("usage ") ||
            t.contains("invalid argument") ||
            t.contains("unrecognized option") ||
            t.contains("too few arguments") ||
            t.contains("expected")
    }
}
