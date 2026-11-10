package com.coolerwave.aimmac

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One screen that swaps its content with the link state, like PhoneRoot on iOS:
 * pick a Mac, wait for approval, then the remote (Pointer or Trackpad).
 */
class MainActivity : Activity() {
    private lateinit var session: PointerSession
    private lateinit var root: FrameLayout

    private var trackpad = false
    private var shown: String? = null

    // live widgets of the remote screen
    private var clickPad: ClickPad? = null
    private var dot: View? = null
    private var macLabel: TextView? = null
    private var pauseIcon: TextView? = null
    private var pauseText: TextView? = null

    private val onChange: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        session = (application as AimApp).session
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        session.listeners.add(onChange)
        if (session.link.hasPermissions()) {
            session.start()
        } else {
            requestPermissions(MacLink.permissions(), REQUEST_BT)
        }
        render()
    }

    override fun onDestroy() {
        session.listeners.remove(onChange)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // coming back from the Bluetooth or app settings screens
        if (session.link.hasPermissions()) session.start()
        session.foreground = true
    }

    override fun onPause() {
        session.release()
        session.foreground = false
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        session.start()
    }

    // MARK: screens

    private fun render() {
        val link = session.link
        val key = when (link.state) {
            MacLink.State.Ready -> "ready:$trackpad"
            MacLink.State.Searching ->
                if (link.hasPairedMac) "connecting"
                else "picker:" + link.found.joinToString(",") { it.address + it.name }
            MacLink.State.Connecting -> "connecting"
            MacLink.State.WaitingApproval -> "wait:" + link.macName
            else -> link.state.name
        }
        if (key != shown) {
            shown = key
            clickPad = null
            dot = null
            macLabel = null
            pauseIcon = null
            pauseText = null
            root.removeAllViews()
            root.addView(build(link.state))
            if (link.state == MacLink.State.Ready) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        refreshLive()
    }

    private fun build(state: MacLink.State): View {
        val link = session.link
        return when (state) {
            MacLink.State.NoPermission -> notice(
                "📡", "Bluetooth access needed",
                "AimMac needs permission to find and talk to your Mac over Bluetooth.",
                "Open app settings"
            ) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            MacLink.State.BluetoothOff -> notice(
                "📡", "Bluetooth is off",
                "Turn on Bluetooth to connect to your Mac.",
                "Turn on Bluetooth"
            ) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            MacLink.State.Searching ->
                if (link.hasPairedMac) connecting() else picker()
            MacLink.State.Connecting -> connecting()
            MacLink.State.WaitingApproval -> notice(
                "🛡", "Allow on your Mac",
                "Click Allow in the window that appeared on " +
                    (if (link.macName.isEmpty()) "your Mac" else link.macName) + "."
            )
            MacLink.State.Denied -> notice(
                "⛔", "Not allowed", "The Mac declined this phone.",
                "Choose another Mac"
            ) { link.unpair() }
            MacLink.State.Ready -> remote()
        }
    }

    private fun connecting(): View = notice(
        "💻", "Connecting…",
        "Make sure AimMac is running on your Mac.",
        "Choose another Mac"
    ) { session.link.unpair() }

    private fun notice(
        icon: String,
        title: String,
        text: String,
        action: String? = null,
        onAction: (() -> Unit)? = null
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        col.addView(Ui.label(this, icon, 44f, Ui.mint))
        col.addView(Ui.label(this, title, 22f, Color.WHITE, bold = true), lpTop(14))
        col.addView(Ui.label(this, text, 15f, Ui.secondary), lpTop(14))
        if (action != null && onAction != null) {
            val b = Button(this).apply {
                this.text = action
                setAllCaps(false)
                setTextColor(Color.WHITE)
                background = Ui.rounded(this@MainActivity, Color.argb(31, 255, 255, 255), 100f)
                setOnClickListener { onAction() }
            }
            col.addView(b, lpTop(22))
        }
        return col
    }

    private fun picker(): View {
        val link = session.link
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        col.addView(Ui.label(this, "AimMac", 34f, Color.WHITE, bold = true).apply {
            gravity = Gravity.START
        })
        col.addView(
            Ui.label(this, "Your phone becomes a pointer for your Mac. Pick the Mac to pair with.", 15f, Ui.secondary)
                .apply { gravity = Gravity.START },
            lpTop(18)
        )

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (link.found.isEmpty()) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = Ui.rounded(this@MainActivity, Ui.panel, 16f)
            }
            row.addView(android.widget.ProgressBar(this), LinearLayout.LayoutParams(dp(24), dp(24)))
            row.addView(
                Ui.label(this, "Looking for Macs running AimMac…", 15f, Ui.secondary).apply {
                    gravity = Gravity.START
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) }
            )
            list.addView(row, lpTop(10))
        }
        for (mac in link.found) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = Ui.rounded(this@MainActivity, Ui.panel, 16f)
                setOnClickListener { link.pair(mac) }
            }
            row.addView(Ui.label(this, "💻", 20f))
            row.addView(
                Ui.label(this, mac.name, 17f, Color.WHITE, bold = true).apply { gravity = Gravity.START },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) }
            )
            val pair = Ui.label(this, "Pair", 14f, Color.BLACK, bold = true).apply {
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = Ui.rounded(this@MainActivity, Ui.mint, 100f)
            }
            row.addView(pair)
            list.addView(row, lpTop(10))
        }
        col.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(match(), 0, 1f))
        return col
    }

    private fun remote(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // header: status dot, Mac name, settings
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val d = View(this).apply { background = Ui.oval(Ui.mint) }
        dot = d
        header.addView(d, LinearLayout.LayoutParams(dp(8), dp(8)))
        val name = Ui.label(this, "Connected", 15f, Color.WHITE, bold = true).apply { gravity = Gravity.START }
        macLabel = name
        header.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8)
        })
        val gear = Ui.label(this, "⚙", 24f).apply {
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { showSettings() }
        }
        header.addView(gear)
        col.addView(header)

        // mode switch
        val seg = LinearLayout(this).apply {
            background = Ui.rounded(this@MainActivity, Ui.panel, 10f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        seg.addView(segment("Pointer", !trackpad) { setMode(false) }, LinearLayout.LayoutParams(0, dp(34), 1f))
        seg.addView(segment("Trackpad", trackpad) { setMode(true) }, LinearLayout.LayoutParams(0, dp(34), 1f))
        col.addView(seg, lpTop(12))

        // pad + scroll strip
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (trackpad) {
            row.addView(TrackSurface(this, session), LinearLayout.LayoutParams(0, match(), 1f))
        } else {
            val pad = ClickPad(this, session)
            clickPad = pad
            row.addView(pad, LinearLayout.LayoutParams(0, match(), 1f))
        }
        row.addView(ScrollStrip(this, session), LinearLayout.LayoutParams(dp(64), match()).apply {
            leftMargin = dp(12)
        })
        col.addView(row, LinearLayout.LayoutParams(match(), 0, 1f).apply { topMargin = dp(12) })

        // buttons
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gap = dp(12)
        buttons.addView(bigButton("🖱", "Right click") { session.rightClick() }, buttonLp(0))
        if (!trackpad) {
            buttons.addView(bigButton("🎯", "Center") { session.center() }, buttonLp(gap))
        }
        val (pauseBtn, pi, pt) = bigButtonParts("⏸", "Pause") {
            session.setPaused(!session.link.paused)
        }
        pauseIcon = pi
        pauseText = pt
        buttons.addView(pauseBtn, buttonLp(gap))
        col.addView(buttons, LinearLayout.LayoutParams(match(), dp(64)).apply { topMargin = dp(12) })
        return col
    }

    private fun setMode(track: Boolean) {
        if (trackpad == track) return
        session.release()
        trackpad = track
        session.aimEnabled = !track
        render()
    }

    private fun segment(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        Ui.label(this, label, 14f, Color.WHITE, bold = selected).apply {
            if (selected) background = Ui.rounded(this@MainActivity, Color.argb(64, 255, 255, 255), 8f)
            setOnClickListener { onClick() }
        }

    private fun bigButton(icon: String, title: String, onClick: () -> Unit): View =
        bigButtonParts(icon, title, onClick).first

    private fun bigButtonParts(
        icon: String,
        title: String,
        onClick: () -> Unit
    ): Triple<LinearLayout, TextView, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.rounded(this@MainActivity, Ui.panel, 16f)
            setOnClickListener { onClick() }
        }
        val i = Ui.label(this, icon, 18f)
        val t = Ui.label(this, title, 12f)
        box.addView(i)
        box.addView(t)
        return Triple(box, i, t)
    }

    private fun buttonLp(left: Int) = LinearLayout.LayoutParams(0, match(), 1f).apply { leftMargin = left }

    /** Update the small things on screen without rebuilding it (a rebuild would drop a finger that is down). */
    private fun refreshLive() {
        val link = session.link
        if (link.state != MacLink.State.Ready) return
        val off = link.macPaused || link.paused
        (dot?.background as? GradientDrawable)?.setColor(if (off) Color.rgb(255, 159, 10) else Ui.mint)
        macLabel?.text = if (link.macName.isEmpty()) "Connected" else link.macName
        pauseIcon?.text = if (link.paused) "▶" else "⏸"
        pauseText?.text = if (link.paused) "Resume" else "Pause"
        clickPad?.setOn(session.pressing)
    }

    private fun showSettings() {
        val link = session.link
        val paired = if (link.macName.isEmpty()) "—" else link.macName
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Settings")
            .setMessage(
                "Paired with: $paired\n\n" +
                    "Pointer: hold the phone like a remote with the top edge toward the screen. " +
                    "Aim at the middle and tap Center.\n\n" +
                    "Speed, smoothing and inverting are set on the Mac, in the AimMac menu."
            )
            .setPositiveButton("Done", null)
            .setNegativeButton("Unpair") { _, _ -> link.unpair() }
            .show()
    }

    // MARK: layout helpers

    private fun dp(v: Int): Int = Ui.dpi(this, v)

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT

    private fun lpTop(topDp: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(topDp) }

    companion object {
        private const val REQUEST_BT = 7
    }
}
