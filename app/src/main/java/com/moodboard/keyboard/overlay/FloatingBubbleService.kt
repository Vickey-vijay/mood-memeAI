package com.moodboard.keyboard.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.moodboard.keyboard.R
import com.moodboard.keyboard.camera.PermissionActivity
import com.moodboard.keyboard.databinding.OverlayBubbleBinding
import com.moodboard.keyboard.databinding.OverlayPanelBinding
import com.moodboard.keyboard.ui.SetupActivity
import com.moodboard.keyboard.util.Prefs
import kotlin.math.abs

/**
 * Foreground service that owns the two `WindowManager` views of SPEC_V3 workstream C:
 * the collapsed draggable bubble and the expanded scan/results panel.
 *
 * ## Foreground-service compliance (SPEC_V3 C.4)
 *  - [startForeground] is the **first** thing [onStartCommand] does, well inside the 5-second
 *    budget, and it runs before any window work that could throw.
 *  - The notification lives on its own `IMPORTANCE_LOW` channel and carries a **Stop** action.
 *  - Declared `foregroundServiceType="specialUse"` with the required
 *    `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property (see AndroidManifest.xml).
 *  - `START_NOT_STICKY`: the system must never resurrect this service on its own, because a
 *    background-initiated FGS start is exactly what Android 12+ forbids. The only legal
 *    start is a user tap in an Activity — [OverlayPermissionActivity] — which is why there is
 *    no BOOT_COMPLETED receiver and no restart-on-kill.
 *  - Overlay permission is re-checked before **every** `addView`, because the user can revoke
 *    "Display over other apps" while the service is alive; when that happens the service
 *    stops itself cleanly instead of throwing `BadTokenException`.
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var themedInflater: LayoutInflater

    private var bubbleBinding: OverlayBubbleBinding? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelBinding: OverlayPanelBinding? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var panelHadFocus = false

    private var controller: OverlayPanelController? = null

    // Drag state for the collapsed bubble.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        // Overlay views are inflated from a Service context, which carries no theme, so wrap
        // it — otherwise ?attr lookups in the layouts resolve to nothing.
        themedInflater = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_MoodBoard))
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // FIRST — inside the 5s startForeground window, before anything that can throw.
        goForeground()

        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.overlay_permission_revoked))
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        prefs.overlayBubbleEnabled = true
        addBubble()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- Foreground notification ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW // low importance: no sound, no heads-up
        ).apply {
            description = getString(R.string.overlay_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Pre-34 platforms don't know the specialUse type; the untyped overload is correct
            // there and avoids passing an unrecognised type bit.
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * P0 crash fix. The bubble itself never touches the camera, so the service starts (and
     * stays) `specialUse`-only until the panel is about to open the camera. On API 34+, a
     * foreground service must currently be running with the `camera` FGS type - and the app
     * must hold [Manifest.permission.CAMERA] at that exact moment - or the system throws
     * `SecurityException` the instant CameraX/Camera2 is touched, killing the process. This
     * elevates the running foreground service's declared type to include `camera` right
     * before [expand] hands off to [OverlayPanelController], and only when the permission is
     * actually held (otherwise the controller's own permission check short-circuits before
     * any camera API is touched, so there is nothing to elevate for). [collapse] reverts to
     * `specialUse`-only once scanning ends. Calling `startForeground` again on an already-
     * foreground service is legal and simply updates its type/notification in place.
     */
    private fun goForegroundWithCameraIfPermitted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } catch (t: Throwable) {
            // If this somehow fails, leave the type as-is; OverlayPanelController's own
            // camera-permission and onError paths still keep camera failures non-fatal.
        }
    }

    /** Reverts the foreground service type back to `specialUse`-only once scanning ends. */
    private fun revertForegroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (t: Throwable) {
            // Non-fatal - worst case the service keeps the camera type declared until it dies.
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val stopIntent = Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 1, stopIntent, flags)

        val openPending = PendingIntent.getActivity(
            this, 2,
            Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setContentText(getString(R.string.overlay_notif_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.overlay_notif_stop), stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ---------------- Collapsed bubble ----------------

    private fun addBubble() {
        if (bubbleBinding != null) return
        val binding = OverlayBubbleBinding.inflate(themedInflater)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            // The bubble stays NOT_FOCUSABLE for its whole life: it must never steal focus
            // from the app underneath, and must never dismiss the host app's keyboard.
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX
            y = if (prefs.bubbleY > 0) prefs.bubbleY else dp(160)
        }

        binding.root.setOnTouchListener { _, event -> onBubbleTouch(event) }

        try {
            wm.addView(binding.root, params)
        } catch (t: Throwable) {
            toast(getString(R.string.overlay_permission_revoked))
            stopSelf()
            return
        }
        bubbleBinding = binding
        bubbleParams = params
    }

    /**
     * SPEC_V3 C.3 drag/tap discrimination: movement under [TAP_SLOP_DP] is a tap, anything
     * more is a drag; on release the bubble snaps to the nearer screen edge and the position
     * is persisted in [Prefs].
     */
    private fun onBubbleTouch(event: MotionEvent): Boolean {
        val params = bubbleParams ?: return false
        val view = bubbleBinding?.root ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = params.x
                downY = params.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > dp(TAP_SLOP_DP) || abs(dy) > dp(TAP_SLOP_DP))) {
                    dragging = true
                }
                if (dragging) {
                    params.x = downX + dx.toInt()
                    params.y = downY + dy.toInt()
                    safeUpdate(view, params)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    snapToEdge(view, params)
                    prefs.bubbleX = params.x
                    prefs.bubbleY = params.y
                } else {
                    togglePanel()
                }
                dragging = false
                return true
            }
        }
        return false
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val width = if (view.width > 0) view.width else dp(56)
        val maxX = (metrics.widthPixels - width).coerceAtLeast(0)
        params.x = if (params.x + width / 2 < metrics.widthPixels / 2) 0 else maxX
        params.y = params.y.coerceIn(0, (metrics.heightPixels - width).coerceAtLeast(0))
        safeUpdate(view, params)
    }

    // ---------------- Expanded panel ----------------

    private fun togglePanel() {
        if (panelBinding == null) expand() else collapse()
    }

    private fun expand() {
        if (panelBinding != null) return
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.overlay_permission_revoked))
            stopSelf()
            return
        }

        val binding = OverlayPanelBinding.inflate(themedInflater)
        // FLAG_LAYOUT_NO_LIMITS (mandated by C.3) means this bottom-gravity window is laid
        // out *through* the navigation/gesture bar, so the action row would sit under it.
        // Pad by the real bottom inset and grow the window by the same amount.
        val bottomInset = systemBottomInset()
        binding.root.setPadding(
            binding.root.paddingLeft,
            binding.root.paddingTop,
            binding.root.paddingRight,
            binding.root.paddingBottom + bottomInset
        )
        val height =
            (resources.displayMetrics.heightPixels * PANEL_HEIGHT_FRACTION).toInt() + bottomInset
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            overlayWindowType(),
            // NOTE (SPEC_V3 C.3): FLAG_NOT_FOCUSABLE is deliberately ABSENT here. A
            // non-focusable overlay window receives no key events and — critically — its
            // RecyclerView will not scroll, because the window never becomes the touch/focus
            // target for fling handling. Clearing the flag on expand is what makes the meme
            // grid usable; setPanelFocusable(false) puts it back on collapse.
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        try {
            wm.addView(binding.root, params)
        } catch (t: Throwable) {
            toast(getString(R.string.overlay_permission_revoked))
            stopSelf()
            return
        }
        panelBinding = binding
        panelParams = params

        // Focusable window => we own BACK while expanded.
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        binding.root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                collapse(); true
            } else false
        }

        // Losing window focus means another window (very plausibly the MoodBoard keyboard
        // itself, opening over a text field) has come forward. Collapse immediately: this is
        // the hard guarantee that the bubble is never holding the front camera at a moment
        // when the IME could want it.
        // Guarded by panelHadFocus: a brand-new window can be dispatched a spurious
        // "no focus" before it has ever been focused, and collapsing on that would slam the
        // panel shut the instant it opens.
        panelHadFocus = false
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) panelHadFocus = true else if (panelHadFocus) collapse()
        }
        binding.root.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        panelFocusListener = focusListener

        bubbleBinding?.root?.visibility = View.GONE

        // P0 crash fix: elevate the FGS type to include "camera" before the controller can
        // possibly touch the camera. Must happen before controller.start(), not after.
        goForegroundWithCameraIfPermitted()

        controller = OverlayPanelController(
            context = ContextThemeWrapper(this, R.style.Theme_MoodBoard),
            binding = binding,
            onNeedCameraPermission = {
                // SPEC_V3 C.4 — reuse the existing transparent permission activity rather
                // than failing silently. A Service cannot show a permission dialog itself.
                startActivity(
                    Intent(this, PermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            onCollapseRequested = { collapse() }
        ).also { it.start() }
    }

    private fun collapse() {
        val binding = panelBinding ?: return

        // Release camera + MediaPipe before the view goes away.
        controller?.release()
        controller = null

        // SPEC_V3 C.3: restore FLAG_NOT_FOCUSABLE. Doing it *before* removing the view hands
        // focus (and the host app's keyboard) straight back instead of leaving a frame where
        // a dying window still owns input.
        setPanelFocusable(false)

        panelFocusListener?.let { binding.root.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
        panelFocusListener = null
        binding.root.setOnKeyListener(null)

        try { wm.removeViewImmediate(binding.root) } catch (_: Throwable) {}
        panelBinding = null
        panelParams = null

        bubbleBinding?.root?.visibility = View.VISIBLE

        // Scanning has ended (controller.release() already ran above) - drop the camera FGS
        // type again so the service only ever claims it while actually in use.
        revertForegroundType()
    }

    /** Toggles `FLAG_NOT_FOCUSABLE` on the live panel window (SPEC_V3 C.3). */
    private fun setPanelFocusable(focusable: Boolean) {
        val binding = panelBinding ?: return
        val params = panelParams ?: return
        params.flags = if (focusable) {
            BASE_FLAGS
        } else {
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        safeUpdate(binding.root, params)
    }

    // ---------------- Teardown ----------------

    override fun onDestroy() {
        isRunning = false
        prefs.overlayBubbleEnabled = false

        controller?.release()
        controller = null

        // Never leak a WindowManager view.
        panelFocusListener?.let { l ->
            panelBinding?.root?.viewTreeObserver?.removeOnWindowFocusChangeListener(l)
        }
        panelFocusListener = null
        panelBinding?.let { b -> try { wm.removeViewImmediate(b.root) } catch (_: Throwable) {} }
        panelBinding = null
        panelParams = null

        bubbleBinding?.let { b ->
            b.root.setOnTouchListener(null)
            try { wm.removeViewImmediate(b.root) } catch (_: Throwable) {}
        }
        bubbleBinding = null
        bubbleParams = null

        super.onDestroy()
    }

    // ---------------- Helpers ----------------

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(view, params) } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** Height of the navigation/gesture bar, so the panel's buttons stay tappable. */
    private fun systemBottomInset(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                .bottom
        } else {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
    } catch (_: Throwable) {
        0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    companion object {
        private const val CHANNEL_ID = "moodboard_overlay"
        private const val NOTIF_ID = 4712
        private const val TAP_SLOP_DP = 10f
        private const val PANEL_HEIGHT_FRACTION = 0.6

        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        const val ACTION_STOP = "com.moodboard.keyboard.overlay.ACTION_STOP"

        /**
         * Live running state, for [SetupActivity]'s card (SPEC_V3 C.6). A static flag is the
         * only accurate answer here: `ActivityManager.getRunningServices` is deprecated and
         * unreliable, and a persisted pref goes stale if the process is killed.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Only ever called from a foreground Activity — see the class doc. */
        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }

        fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }
}
