package com.shadow.injector

import android.content.Context

/**
 * Handles everything that happens as root: staging the two libraries where the game can
 * load them, finding the right PID, and running the injector binary.
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

        val r = RootShell.root(script, timeoutSec = 60)

        return StageResult(
            ok = isStaged(),
            log = (r.out + r.err).trim()
        )
    }

    data class StageResult(val ok: Boolean, val log: String)

    /** True when both files are already in place and executable. */
    fun isStaged(): Boolean {
        val r = RootShell.root(
            "[ -x '${InjectorConfig.INJECTOR_BIN}' ] " +
                "&& [ -s '${InjectorConfig.PAYLOAD_PATH}' ] && echo YES",
            timeoutSec = 15
        )
        return r.out.contains("YES")
    }

    /** Staging is ~5 MB of shell I/O, so only redo it when the payload actually changed. */
    fun needsStaging(context: Context): Boolean {
        if (!isStaged()) return true
        val local = java.io.File(context.applicationInfo.nativeLibraryDir, InjectorConfig.PAYLOAD_LIB)
        val remote = RootShell.root(
            "stat -c %s '${InjectorConfig.PAYLOAD_PATH}' 2>/dev/null",
            timeoutSec = 10
        ).out.trim().toLongOrNull() ?: 0L
        return local.length() != remote
    }

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

    /** Is [name] mapped into this process? Read from /proc/<pid>/maps. */
    fun hasLibraryMapped(pid: Int, name: String): Boolean {
        if (pid <= 0) return false
        val r = RootShell.root(
            "grep -q '$name' /proc/$pid/maps 2>/dev/null && echo YES",
            timeoutSec = 10
        )
        return r.out.contains("YES")
    }

    /** True once the Unity runtime is loaded and the payload can resolve its symbols. */
    fun isRuntimeReady(pid: Int): Boolean =
        InjectorConfig.UNITY_MARKERS.any { hasLibraryMapped(pid, it) }

    /**
     * Polls until the game process exists and — when WAIT_FOR_UNITY is on — until the Unity
     * runtime is mapped. Returns the PID, or 0 if nothing usable showed up in time.
     *
     * [onProgress] is called with human readable status while we wait.
     */
    fun awaitGameProcess(
        processName: String,
        onProgress: (String) -> Unit
    ): Int {
        val deadline = System.currentTimeMillis() + InjectorConfig.PID_TIMEOUT_MS
        val unityDeadline = System.currentTimeMillis() + InjectorConfig.UNITY_TIMEOUT_MS
        var fallback = 0
        var announced = false

        while (System.currentTimeMillis() < deadline) {
            val pids = pidsOf(processName).filter { it > 0 }
            if (pids.isEmpty()) {
                Thread.sleep(400)
                continue
            }
            if (fallback == 0) fallback = pids.first()

            val ready = pids.firstOrNull { isRuntimeReady(it) }
            if (ready != null) return ready

            if (!InjectorConfig.WAIT_FOR_UNITY) return fallback

            if (!announced) {
                announced = true
                onProgress("Process up (PID ${fallback}) — waiting for the Unity runtime to load…")
            }
            if (System.currentTimeMillis() > unityDeadline) break
            Thread.sleep(700)
        }

        if (fallback == 0) {
            val lastChance = pidOf(processName)
            if (lastChance > 0) return lastChance
            return 0
        }

        return if (InjectorConfig.INJECT_ANYWAY_ON_TIMEOUT) {
            onProgress("Runtime markers not seen — injecting into PID $fallback anyway.")
            fallback
        } else {
            0
        }
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
