package com.droidos.neo

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnEnableAccessibility: Button
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvEndpointUrl: TextView
    private lateinit var tvLogs: TextView
    private lateinit var switchCloudflare: Switch
    private lateinit var tvCloudflareStatus: TextView

    private var cfClient: McpCloudflareClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        tvAccessibilityStatus  = findViewById(R.id.tvAccessibilityStatus)
        tvEndpointUrl          = findViewById(R.id.tvEndpointUrl)
        tvLogs                 = findViewById(R.id.tvLogs)
        switchCloudflare       = findViewById(R.id.switchCloudflare)
        tvCloudflareStatus     = findViewById(R.id.tvCloudflareStatus)

        // Open accessibility settings when button pressed
        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Wire log callback from accessibility service
        DroidOSAccessibilityService.Companion.lastLogCallback = { logLine ->
            runOnUiThread {
                val current = tvLogs.text.toString()
                tvLogs.text = "$logLine\n$current"
            }
        }

        // Initialise Cloudflare client (not auto-started)
        cfClient = McpCloudflareClient(applicationContext).also { client ->
            client.setOnLogMessage { msg ->
                runOnUiThread {
                    val current = tvLogs.text.toString()
                    tvLogs.text = "$msg\n$current"
                }
            }
        }

        // Restore persisted switch state (default OFF)
        val savedCfEnabled = cfClient!!.isModeEnabled()
        switchCloudflare.isChecked = savedCfEnabled
        updateCloudflareStatus(savedCfEnabled)
        if (savedCfEnabled) cfClient?.start()

        // Toggle handler
        switchCloudflare.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                cfClient?.start()
                updateCloudflareStatus(true)
            } else {
                cfClient?.stop()
                updateCloudflareStatus(false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    // ── UI helpers ───────────────────────────────────────────────────────────
    private fun updateCloudflareStatus(enabled: Boolean) {
        tvCloudflareStatus.text = if (enabled) "🌐 Connected to CF" else "📱 Local Mode"
    }

    private fun updateUiState() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            tvAccessibilityStatus.text = "Accessibility Service: Active (Ready for Agents)"
            btnEnableAccessibility.text = "Accessibility Settings"

            val ip   = NetworkUtils.getLocalIpAddress()
            val port = 9239
            tvEndpointUrl.text = "Endpoint: http://$ip:$port/mcp"
        } else {
            tvAccessibilityStatus.text = "Accessibility Service: Not Enabled"
            btnEnableAccessibility.text = "Grant Accessibility Permission"
            tvEndpointUrl.text = "Accessibility Service is not enabled."
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService("accessibility") as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = enabledServices.split(":")
        val myService = "$packageName/${DroidOSAccessibilityService::class.java.name}"
        return colonSplitter.any { it.equals(myService, ignoreCase = true) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop CF client on destroy — it persists to background if enabled
    }
}
