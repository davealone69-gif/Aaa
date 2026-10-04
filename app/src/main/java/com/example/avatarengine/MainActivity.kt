package com.example.avatarengine

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.avatar.sdk.api.AdultModeSettings
import com.example.avatar.sdk.api.AvatarAsset
import com.example.avatar.sdk.api.AvatarEngine
import com.example.avatar.sdk.render.AvatarView

class MainActivity : Activity() {
    private lateinit var engine: AvatarEngine
    private lateinit var status: TextView
    private lateinit var preview: AvatarView
    private lateinit var exportButton: Button
    private lateinit var adultModeButton: Button
    private lateinit var adultSettings: AdultModeSettings
    private var currentAsset: AvatarAsset? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AvatarEngine.initialize(this)
        adultSettings = AdultModeSettings(this)
        preview = AvatarView(this)
        status = TextView(this).apply { text = "No avatar loaded. Import a real .glb asset." }
        val import = Button(this).apply {
            text = "Import GLB"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "model/gltf-binary"; addCategory(Intent.CATEGORY_OPENABLE) }, 42) }
        }
        exportButton = Button(this).apply {
            text = "Export GLB"
            isEnabled = false
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "model/gltf-binary"; putExtra(Intent.EXTRA_TITLE, "avatar.glb") }, 43) }
        }
        adultModeButton = Button(this).apply { setOnClickListener { toggleAdultMode() } }
        refreshAdultModeButton()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status)
            addView(adultModeButton)
            addView(import)
            addView(exportButton)
        })
    }

    private fun toggleAdultMode() {
        if (adultSettings.enabled) {
            adultSettings.disable(); refreshAdultModeButton(); status.text = "Adult mode disabled; normal mode is active"
        } else {
            AlertDialog.Builder(this)
                .setTitle("Enable adult mode")
                .setMessage("Adult mode is optional and off by default. Confirm that you are 18 or older. This setting is not proof of identity or anyone's actual age. Reference photos remain disabled in adult mode.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("I am 18+; enable") { _, _ -> adultSettings.enableAfterExplicit18PlusConfirmation(true); refreshAdultModeButton(); status.text = "Adult mode enabled locally; provider capability is checked before any request" }
                .show()
        }
    }

    private fun refreshAdultModeButton() { adultModeButton.text = if (adultSettings.enabled) "Adult mode: ON (tap to disable)" else "Adult mode: OFF (safe mode)" }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            42 -> runCatching {
                val asset = engine.importGlb(data.data!!)
                currentAsset = asset
                preview.loadGlb(asset.glb)
                exportButton.isEnabled = true
                status.text = "Loaded and validated ${asset.metadata.id}"
            }.onFailure { status.text = "Import failed: ${it.message}" }
            43 -> runCatching {
                val asset = requireNotNull(currentAsset) { "No avatar loaded" }
                engine.exportGlb(asset.metadata.id, data.data!!)
                status.text = "Exported validated GLB"
            }.onFailure { status.text = "Export failed: ${it.message}" }
        }
    }
}
