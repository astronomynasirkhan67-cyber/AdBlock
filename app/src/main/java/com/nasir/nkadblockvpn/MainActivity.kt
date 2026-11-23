package com.nasir.nkadblockvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import android.content.Context
import android.os.Build

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 15, 27)
    private val panel = Color.rgb(17, 31, 48)
    private val green = Color.rgb(53, 224, 161)
    private val muted = Color.rgb(158, 176, 195)
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var counter: TextView
    private lateinit var statusDot: View
    private var refreshHandler: android.os.Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        buildUi()
        refresh()
    }

    private fun rounded(color: Int, radius: Float = 22f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius * resources.displayMetrics.density }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); clipToPadding = false }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(52), dp(22), dp(30))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.nk_logo)
            background = rounded(panel, 18f)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        top.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }
        brand.addView(text("NK SECURITY", 12f, green, true))
        brand.addView(text("PURE INTERNET", 17f, Color.WHITE, true))
        top.addView(brand)
        page.addView(top)

        page.addView(text("Ad Block VPN", 31f, Color.WHITE, true).apply {
            setPadding(0, dp(28), 0, dp(3))
        })
        page.addView(text("Network-wide DNS protection", 15f, muted))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(panel, 24f)
            setPadding(dp(20), dp(25), dp(20), dp(22))
        }
        val shield = ImageView(this).apply {
            setImageResource(R.drawable.nk_logo)
            background = rounded(Color.rgb(24, 58, 54), 60f)
            setPadding(dp(13), dp(13), dp(13), dp(13))
        }
        hero.addView(shield, LinearLayout.LayoutParams(dp(82), dp(82)))
        statusDot = View(this).apply { background = rounded(Color.GRAY, 50f) }
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, dp(5))
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(9) })
        status = text("PROTECTION OFF", 18f, Color.WHITE, true)
        statusRow.addView(status)
        hero.addView(statusRow)
        hero.addView(text("Your connection is filtered on this device.", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(19))
        })
        toggle = Button(this).apply {
            text = "START PROTECTION"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(bg)
            background = rounded(green, 16f)
            minHeight = dp(54)
            setOnClickListener { toggleProtection() }
        }
        hero.addView(toggle, LinearLayout.LayoutParams(-1, dp(56)))
        page.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(25) })

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(13, 39, 43), 20f)
            setPadding(dp(18), dp(17), dp(18), dp(17))
        }
        stats.addView(text("FILTERING ACTIVITY", 11f, muted, true))
        counter = text("0", 30f, green, true)
        stats.addView(counter)
        stats.addView(text("Blocked DNS requests this session", 12f, muted))
        page.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val features = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 20f)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        features.addView(text("PROTECTION FEATURES", 12f, green, true))
        listOf(
            "DNS ad and tracker domain filtering",
            "On-device VPN — no remote VPN server",
            "Local processing; traffic is not routed through our server",
            "Session blocked-request counter"
        ).forEach { item ->
            features.addView(text("✓  $item", 13f, Color.rgb(218, 228, 239)).apply {
                setPadding(0, dp(12), 0, 0)
            })
        }
        page.addView(features, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(38, 34, 24), 18f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        note.addView(text("PLEASE NOTE", 12f, Color.rgb(255, 203, 112), true))
        note.addView(text(
            "DNS filtering cannot reliably remove ads delivered from the same domains as app content, including many YouTube video ads. Some apps may show empty ad spaces or have loading issues. Android normally allows only one active VPN at a time.",
            12f, Color.rgb(226, 216, 196)
        ).apply { setPadding(0, dp(8), 0, 0) })
        page.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        page.addView(text("NK AD BLOCK VPN  •  VERSION 1.0.1", 10f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
        scroll.addView(page)
        setContentView(scroll)
    }

    private fun toggleProtection() {
        if (AdBlockVpnService.running) {
            val stopIntent = Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(stopIntent) else startService(stopIntent)
            window.decorView.postDelayed({ refresh() }, 500)
            return
        }
        try {
            val prepare = VpnService.prepare(this)
            if (prepare != null) startActivityForResult(prepare, 71)
            else startVpnService()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not prepare VPN: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startVpnService() {
        try {
            val intent = Intent(this, AdBlockVpnService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            window.decorView.postDelayed({ refresh() }, 900)
        } catch (e: Exception) {
            Toast.makeText(this, "VPN start failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Legacy VPN consent callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 71) {
            if (resultCode == RESULT_OK) startVpnService()
            else Toast.makeText(this, "VPN permission was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler = android.os.Handler(mainLooper)
        refreshHandler?.post(refreshRunnable)
    }

    override fun onPause() {
        refreshHandler?.removeCallbacks(refreshRunnable)
        refreshHandler = null
        super.onPause()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            refreshHandler?.postDelayed(this, 1000)
        }
    }

    private fun refresh() {
        val on = AdBlockVpnService.running
        if (!::status.isInitialized) return
        status.text = if (on) "PROTECTION ACTIVE" else "PROTECTION OFF"
        status.setTextColor(if (on) green else Color.WHITE)
        statusDot.background = rounded(if (on) green else Color.GRAY, 50f)
        toggle.text = if (on) "STOP PROTECTION" else "START PROTECTION"
        toggle.background = rounded(if (on) Color.rgb(255, 103, 103) else green, 16f)
        toggle.setTextColor(if (on) Color.WHITE else bg)
        counter.text = AdBlockVpnService.blockedCount.get().toString()
    }
}
