package com.shadow.injector

/**
 * Everything you may need to tune lives here.
 *
 * NOTE ON THE TWO LIBRARIES
 * -------------------------
 *  libRootMagisk.so  -> despite the name this is a **PIE executable** (it has an INTERP segment
 *                       pointing at /system/bin/linker64, a real entry point, and it imports
 *                       atoi / ptrace / waitpid / dl_open helpers and reads /proc/<pid>/maps).
 *                       It is therefore *executed* as root, not dlopen()ed.
 *                       We copy it out under the name "shadow-injector" and run it.
 *
 *  libmain.so        -> the payload that actually lands inside the game. It imports the IL2CPP
 *                       API (il2cpp_domain_get, il2cpp_class_from_name, ...), libunity.so,
 *                       libil2cpp.so, libEGL.so and libGLESv2.so, and exports JNI_OnLoad.
 *                       So it is a Unity / IL2CPP mod payload, loaded with dlopen() by the
 *                       injector above.
 */
object InjectorConfig {

    /** Name of the injector binary inside the APK's lib/arm64 folder. */
    const val INJECTOR_LIB = "libRootMagisk.so"

    /** Name of the payload library inside the APK's lib/arm64 folder. */
    const val PAYLOAD_LIB = "libmain.so"

    /** World readable scratch dir both root and the game can reach. */
    const val WORK_DIR = "/data/local/tmp/shadow"

    /** Path the injector binary is staged to (executable bit set). */
    const val INJECTOR_BIN = "$WORK_DIR/shadow-injector"

    /** Path the payload is staged to. */
    const val PAYLOAD_PATH = "$WORK_DIR/$PAYLOAD_LIB"

    /**
     * Argument templates handed to the injector binary. `{pid}` and `{lib}` are substituted.
     *
     * The binary resolves its target with atoi() and then reads /proc/<pid>/maps, so
     * "<pid> <lib>" is almost certainly right — the other ordering is tried automatically
     * as a fallback, and you can add more here if your build uses flags.
     */
    val ARG_TEMPLATES = listOf(
        "'$INJECTOR_BIN' {pid} '{lib}'",
        "'$INJECTOR_BIN' '{lib}' {pid}"
    )

    /** How long to poll for the game process after launching it. */
    const val PID_TIMEOUT_MS = 30_000L

    /** If every argument template fails, retry once with SELinux in permissive mode. */
    const val RETRY_PERMISSIVE = true
}
