package com.nasir.nkadblockvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import android.content.Context

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var counter: TextView
    private val prefs by lazy { getSharedPreferences("nk", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 15, 27)
        window.navigationBarColor = Color.rgb(8, 15, 27)
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val bg = Color.rgb(8, 15, 27)
        val panel = Color.rgb(18, 31, 49)
        val green = Color.rgb(34, 220, 145)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 34, 24, 24)
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = TextView(this).apply {
            text = "NK  /  PURE INTERNET"
            textSize = 13f; setTextColor(green); letterSpacing = .16f
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(brand)
        content.addView(TextView(this).apply {
            text = "Ad Block VPN"; textSize = 30f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 10, 0, 4)
        })
        content.addView(TextView(this).apply {
            text = "Network-wide DNS protection"; textSize = 14f
            setTextColor(Color.LTGRAY)
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(18, 24, 18, 24); setBackgroundColor(panel)
        }
        status = TextView(this).apply {
            text = "PROTECTION OFF"; textSize = 18f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        toggle = Button(this).apply {
            text = "START PROTECTION"; isAllCaps = false
            setTextColor(bg); setBackgroundColor(green)
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { startProtection() }
        }
        card.addView(status)
        card.addView(TextView(this).apply {
            text = "Your DNS requests are filtered locally on this device."
            textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            setPadding(0, 12, 0, 18)
        })
        card.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 28 })
        counter = TextView(this).apply {
            text = "0  domains blocked"; textSize = 18f; setTextColor(green)
            gravity = Gravity.CENTER; setPadding(0, 26, 0, 10)
        }
        content.addView(counter)
        val info = TextView(this).apply {
            text = "FEATURES\n• DNS-based ad and tracker filtering\n• Local VPN — no remote VPN server\n• Customizable protection lists\n• Lightweight on-device processing\n\nIMPORTANT\nDNS filtering cannot reliably remove ads served from the same domains as app content (including many YouTube video ads). Some apps may show blank ad spaces or stop loading content. Only one VPN can normally be active at a time."
            textSize = 14f; setTextColor(Color.rgb(205, 216, 229))
            setPadding(18, 18, 18, 18); setBackgroundColor(panel)
        }
        content.addView(info)
        content.addView(TextView(this).apply {
            text = "NK Ad Block VPN  •  Version 1.0.0"
            textSize = 11f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        })
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun startProtection() {
        if (AdBlockVpnService.running) {
            stopService(Intent(this, AdBlockVpnService::class.java))
            refresh()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) startActivityForResult(prepare, 71)
        else onActivityResult(71, RESULT_OK, null)
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 71 && resultCode == RESULT_OK) {
            startForegroundService(Intent(this, AdBlockVpnService::class.java))
            window.decorView.postDelayed({ refresh() }, 700)
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val on = AdBlockVpnService.running
        status.text = if (on) "PROTECTION ACTIVE" else "PROTECTION OFF"
        status.setTextColor(if (on) Color.rgb(34,220,145) else Color.WHITE)
        toggle.text = if (on) "STOP PROTECTION" else "START PROTECTION"
        counter.text = "${AdBlockVpnService.blockedCount}  blocked DNS requests"
    }
}
