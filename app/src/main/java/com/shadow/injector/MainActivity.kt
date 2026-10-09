package com.shadow.injector

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREF_PROMO_DONE = "promo_telegram_done"
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var starfield: StarfieldView

    private lateinit var pillOverlay: TextView
    private lateinit var pillRoot: TextView
    private lateinit var pillPayload: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var btnRoot: TextView
    private lateinit var btnInject: Button
    private lateinit var tvLog: TextView

    /** row view -> target, keeps single-selection logic trivial */
    private val rows = LinkedHashMap<View, GameTarget>()
    private val badges = LinkedHashMap<String, TextView>()

    private var selected: GameTarget? = null
    private var rootOk = false
    private var payloadOk = false
    private var busy = false

    private val logBuf = ArrayList<Pair<String, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        starfield = findViewById(R.id.starfield)

        pillOverlay = findViewById(R.id.pillOverlay)
        pillRoot = findViewById(R.id.pillRoot)
        pillPayload = findViewById(R.id.pillPayload)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnRoot = findViewById(R.id.btnRoot)
        btnInject = findViewById(R.id.btnInject)
        tvLog = findViewById(R.id.tvLog)

        setupTargets()
        setupActions()

        log("SHADOW INJECTOR v${BuildConfig.VERSION_NAME}", R.color.accent)
        log("arm64 · libmain.so (IL2CPP) · libRootMagisk.so (ptrace)", R.color.text_dim)
        log("Waiting for overlay permission + root access…", R.color.text_dim)

        checkForUpdateOnStart()
        main.postDelayed({ maybeShowTelegramPromo() }, 700)
    }

    override fun onStart() {
        super.onStart()
        starfield.resume()
    }

    override fun onStop() {
        // Stop burning CPU on the animation while the game is on screen.
        starfield.pause()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI setup

    private fun setupTargets() {
        val bindings = listOf(
            R.id.targetFf to Targets.FREE_FIRE,
            R.id.targetMax to Targets.FREE_FIRE_MAX,
            R.id.targetAdv to Targets.FREE_FIRE_ADVANCED
        )
        for ((rowId, target) in bindings) {
            val row = findViewById<View>(rowId)
            row.findViewById<TextView>(R.id.txtName).text = target.label
            row.findViewById<TextView>(R.id.txtPackage).text = target.pkg
            row.findViewById<ImageView>(R.id.imgIcon).setImageResource(target.icon)
            badges[target.id] = row.findViewById(R.id.badgeInstalled)
            rows[row] = target
            row.setOnClickListener { selectTarget(target) }
        }
    }

    private fun setupActions() {
        btnOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                stopService(Intent(this, OverlayService::class.java))
                Toast.makeText(this, "Overlay hidden", Toast.LENGTH_SHORT).show()
            } else {
                requestOverlayPermission()
            }
        }
        btnRoot.setOnClickListener { checkRoot() }
        btnInject.setOnClickListener { runInjectionFlow() }
    }

    private fun selectTarget(target: GameTarget) {
        selected = target
        for ((row, t) in rows) {
            val on = t.id == target.id
            row.isSelected = on
            row.findViewById<View>(R.id.imgCheck).visibility =
                if (on) View.VISIBLE else View.GONE
        }
        log("Target → ${target.label} (${target.pkg})", R.color.accent)
        refreshInjectButton()
    }

    // ------------------------------------------------------------------ status

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        setPill(pillOverlay, overlay, if (overlay) "GRANTED" else "DENIED")
        btnOverlay.text = if (overlay) "HIDE" else "GRANT"

        checkPayload()
        checkRoot()
        refreshInstallBadges()
        refreshInjectButton()
    }

    private fun checkPayload() {
        val dir = applicationInfo.nativeLibraryDir
        val injector = java.io.File(dir, InjectorConfig.INJECTOR_LIB)
        val payload = java.io.File(dir, InjectorConfig.PAYLOAD_LIB)
        payloadOk = injector.isFile && payload.isFile
        setPill(
            pillPayload,
            payloadOk,
            if (payloadOk) "READY · arm64" else "MISSING"
        )
        if (!payloadOk) {
            log("Payload missing from $dir — rebuild with useLegacyPackaging=true", R.color.bad)
        }
    }

    private fun checkRoot() {
        setPill(pillRoot, null, "CHECKING")
        bg.execute {
            val ok = RootShell.hasRoot()
            val impl = if (ok) RootShell.rootImpl() else null
            main.post {
                rootOk = ok
                setPill(
                    pillRoot,
                    ok,
                    when {
                        !ok -> "NOT FOUND"
                        impl != null -> "GRANTED · $impl"
                        else -> "GRANTED"
                    }
                )
                if (!ok) log("No root. Allow su for this app, then press RECHECK.", R.color.bad)
                else log("Root shell active${if (impl != null) " via $impl" else ""}.", R.color.ok)
                refreshInjectButton()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshInstallBadges() {
        for (target in Targets.ALL) {
            val badge = badges[target.id] ?: continue
            val installed = try {
                packageManager.getPackageInfo(target.pkg, 0) != null
            } catch (e: Exception) {
                false
            }
            badge.text = if (installed) "INSTALLED" else "NOT FOUND"
            badge.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (installed) R.color.ok else R.color.text_dim
                )
            )
        }
    }

    private fun refreshInjectButton() {
        val ready = !busy && selected != null && rootOk && payloadOk &&
            Settings.canDrawOverlays(this)
        btnInject.isEnabled = ready
        btnInject.alpha = if (ready) 1f else 0.42f
    }

    private fun setPill(pill: TextView, ok: Boolean?, text: String) {
        pill.text = text
        pill.setTextColor(
            ContextCompat.getColor(
                this,
                when (ok) {
                    true -> R.color.ok
                    false -> R.color.bad
                    null -> R.color.warn
                }
            )
        )
        pill.setBackgroundResource(
            when (ok) {
                true -> R.drawable.pill_ok
                false -> R.drawable.pill_bad
                null -> R.drawable.pill_warn
            }
        )
    }

    // ------------------------------------------------------------------ injection

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Allow “Display over other apps”", Toast.LENGTH_LONG).show()
        }
    }

    private fun runInjectionFlow() {
        val target = selected
        if (busy || target == null) return

        if (!Settings.canDrawOverlays(this)) {
            log("Overlay permission missing — grant it first.", R.color.bad)
            requestOverlayPermission()
            return
        }
        if (!rootOk) {
            log("Root access missing — grant su and press RECHECK.", R.color.bad)
            return
        }
        if (!payloadOk) {
            log("Payload libraries missing from the APK.", R.color.bad)
            return
        }

        busy = true
        btnInject.text = getString(R.string.injecting)
        refreshInjectButton()

        // Start the overlay chip NOW, while this activity is still in the foreground:
        // once the game launches our app is backgrounded and startService() would be
        // refused by Android 8+. The chip is updated again when injection finishes.
        startOverlayChip(getString(R.string.injecting))

        bg.execute { inject(target) }
    }

    private fun inject(target: GameTarget) {
        var injected = false
        try {
            log("──── ${target.label} · ${target.pkg} ────", R.color.accent_alt)

            // 1. Stage both libraries where root and the game can reach them (only if changed).
            if (!Injector.needsStaging(this)) {
                log("Payload already staged in ${InjectorConfig.WORK_DIR}", R.color.text_dim)
            } else {
                log("Staging payload → ${InjectorConfig.WORK_DIR}", R.color.text_dim)
                val staged = Injector.stage(this)
                if (staged.log.isNotBlank()) {
                    staged.log.lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { log("   $it", R.color.text_dim) }
                }
                if (!staged.ok) {
                    log("Staging failed — see output above.", R.color.bad)
                    return
                }
                log("Payload staged (injector + libmain.so, 0755).", R.color.ok)
            }

            // 2. Restart the game so we inject into a fresh process.
            log("Restarting ${target.pkg}…", R.color.text_dim)
            RootShell.root("am force-stop ${target.pkg}", timeoutSec = 15)
            Thread.sleep(700)

            if (!launch(target.pkg)) {
                log("Could not launch ${target.label} — is the game installed?", R.color.bad)
                return
            }
            log("Launch intent sent.", R.color.text_dim)

            // 3. Wait for the process, and for the Unity runtime to be mapped.
            //    Injecting before libil2cpp.so is loaded is what makes the payload die a
            //    minute or two later (or instantly), so this wait is deliberate.
            val pid = Injector.awaitGameProcess(target.processName) { log(it, R.color.text_dim) }
            if (pid <= 0) {
                log("Game process never became ready — aborting.", R.color.bad)
                return
            }
            log("Target PID = $pid (runtime ready: ${Injector.isRuntimeReady(pid)})", R.color.text_dim)

            // 4. Run the injector binary. Try every argument template.
            val candidates = Injector.commandCandidates(pid)
            for ((index, command) in candidates.withIndex()) {
                log("Injecting… (argument order ${index + 1}/${candidates.size})", R.color.text_dim)
                val r = RootShell.root(command, timeoutSec = 45)
                r.out.lineSequence().filter { it.isNotBlank() }.forEach { log("   $it") }
                r.err.lineSequence().filter { it.isNotBlank() }.forEach { log("   ! $it", R.color.bad) }

                if (r.exit == 0 && !Injector.looksLikeUsageError(r.combined)) {
                    injected = true
                    break
                }
                if (Injector.looksLikeUsageError(r.combined)) {
                    log("   injector rejected those arguments, trying next order…", R.color.warn)
                }
            }

            // 5. Last resort: permissive SELinux blocks nobody from dlopen()ing.
            if (!injected && InjectorConfig.RETRY_PERMISSIVE) {
                log("Retrying with SELinux set to permissive…", R.color.warn)
                RootShell.root("setenforce 0", timeoutSec = 10)
                val retry = RootShell.root(candidates.first(), timeoutSec = 45)
                retry.out.lineSequence().filter { it.isNotBlank() }.forEach { log("   $it") }
                retry.err.lineSequence().filter { it.isNotBlank() }.forEach { log("   ! $it", R.color.bad) }
                injected = retry.exit == 0 && !Injector.looksLikeUsageError(retry.combined)
            }

            if (injected) {
                log("✔ libmain.so injected into ${target.label} (PID $pid)", R.color.ok)
                startOverlayChip(target.label)
                log("Game is on top — the SHADOW chip is your status indicator.", R.color.text_dim)
            } else {
                stopOverlayChip()
                log("✖ Injection failed. Read the injector output above and, if needed,", R.color.bad)
                log("  adjust InjectorConfig.ARG_TEMPLATES for your binary.", R.color.bad)
            }
        } catch (e: InterruptedException) {
            log("Injection cancelled.", R.color.warn)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log("Error: ${e.message}", R.color.bad)
        } finally {
            main.post {
                busy = false
                btnInject.text = getString(R.string.inject)
                refreshInjectButton()
            }
        }
    }

    /** Launches the game from the UI thread and waits for the intent to go out. */
    private fun launch(pkg: String): Boolean {
        val launched = AtomicBoolean(false)
        main.post {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                runCatching {
                    startActivity(intent)
                    launched.set(true)
                }
            }
        }
        repeat(50) {
            if (launched.get()) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun startOverlayChip(label: String, pkg: String? = null) {
        if (!Settings.canDrawOverlays(this)) return
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_LABEL, label.uppercase())
            putExtra(OverlayService.EXTRA_PACKAGE, pkg.orEmpty())
        }
        runCatching { startService(intent) }
    }

    private fun stopOverlayChip() {
        runCatching { stopService(Intent(this, OverlayService::class.java)) }
    }

    // ------------------------------------------------------------------ dialogs

    /** Telegram promo — shown once, then never again. JOIN opens the link, CLOSE dismisses. */
    private fun maybeShowTelegramPromo() {
        if (isFinishing || isDestroyed) return
        val prefs = getSharedPreferences("shadow", MODE_PRIVATE)
        if (prefs.getBoolean(PREF_PROMO_DONE, false)) return

        val view = layoutInflater.inflate(R.layout.dialog_promo, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val dismissPermanently = {
            prefs.edit().putBoolean(PREF_PROMO_DONE, true).apply()
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnJoin).setOnClickListener {
            Updater.openUrl(this, Updater.TELEGRAM_URL)
            dismissPermanently()
        }
        view.findViewById<View>(R.id.btnClose).setOnClickListener { dismissPermanently() }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun checkForUpdateOnStart() {
        bg.execute {
            val info = Updater.checkUpdate()
            if (info != null) main.post { showUpdateDialog(info) }
        }
    }

    private fun showUpdateDialog(info: Updater.Info) {
        if (isFinishing || isDestroyed) return
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.txtUpdateVersion).text = "v${info.versionName}"
        view.findViewById<TextView>(R.id.txtUpdateNotes).text = info.notes

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(!info.force)
            .create()

        val later = view.findViewById<View>(R.id.btnUpdateLater)
        later.visibility = if (info.force) View.GONE else View.VISIBLE
        later.setOnClickListener { dialog.dismiss() }

        view.findViewById<View>(R.id.btnUpdateNow).setOnClickListener {
            dialog.dismiss()
            downloadAndInstall(info)
        }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun downloadAndInstall(info: Updater.Info) {
        log("Downloading update v${info.versionName}…", R.color.accent)
        bg.execute {
            val apk = Updater.download(this, info)
            main.post {
                if (apk == null) {
                    log("Update download failed — check your connection.", R.color.bad)
                    return@post
                }
                log("Update downloaded (${apk.length() / 1024} KB).", R.color.ok)
                if (!Updater.canInstallPackages(this)) {
                    log("Allow “install unknown apps” for Shadow Injector, then retry.", R.color.warn)
                    Updater.openInstallPermissionSettings(this)
                } else {
                    Updater.install(this, apk)
                }
            }
        }
    }

    // ------------------------------------------------------------------ session log

    private fun log(text: String, @ColorRes color: Int = R.color.text_secondary) {
        main.post {
            logBuf.add(text to color)
            while (logBuf.size > 120) logBuf.removeAt(0)

            val sb = SpannableStringBuilder()
            logBuf.forEachIndexed { index, entry ->
                if (index > 0) sb.append('\n')
                val span = SpannableString(entry.first).apply {
                    setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, entry.second)),
                        0,
                        length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                sb.append(span)
            }
            tvLog.text = sb
        }
    }
}
