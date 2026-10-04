package com.example.avatarengine

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.avatar.sdk.api.AdultModeSettings
import com.example.avatar.sdk.api.AvatarAsset
import com.example.avatar.sdk.api.AvatarEngine
import com.example.avatar.sdk.storage.ProjectRecord
import com.example.avatar.sdk.storage.SceneRecord
import com.example.avatar.sdk.storage.StudioRepository
import com.example.avatar.sdk.storage.ReferenceRecord
import com.example.avatar.sdk.render.AvatarView

class MainActivity : Activity() {
    private lateinit var engine: AvatarEngine
    private lateinit var studio: StudioRepository
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var preview: AvatarView
    private lateinit var adultModeButton: Button
    private lateinit var adultSettings: AdultModeSettings
    private var currentAsset: AvatarAsset? = null
    private var currentProject: ProjectRecord? = null
    private var currentScene: SceneRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AvatarEngine.initialize(this)
        studio = StudioRepository(this)
        adultSettings = AdultModeSettings(this)
        showProjects()
    }

    private fun base(title: String): LinearLayout {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = title; textSize = 24f })
        status = TextView(this).apply { text = "Ready"; setPadding(0, 12, 0, 12) }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
        return root
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { runCatching(action).onFailure { showError(it) } } }
    private fun showError(error: Throwable) { status.text = "Error: ${error.message ?: error::class.java.simpleName}" }
    private fun info(message: String) { status.text = message; Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun prompt(title: String, initial: String = "", onSubmit: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); hint = title }
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> onSubmit(input.text.toString()) }.show()
    }

    private fun showProjects() {
        val layout = base("Aaa Studio · Projects")
        layout.addView(button("Create project") { prompt("Project name") { name -> val project = studio.createProject(name); showProject(project) } })
        val projects = studio.listProjects()
        if (projects.isEmpty()) layout.addView(TextView(this).apply { text = "No projects yet. Create a project to begin."; setPadding(0, 24, 0, 24) })
        projects.forEach { project ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
            row.addView(TextView(this).apply { text = "${project.name}\nUpdated ${project.updatedAt}" })
            row.addView(button("Open") { showProject(project) })
            row.addView(button("Rename") { prompt("Project name", project.name) { showProject(studio.renameProject(project.projectId, it)) } })
            row.addView(button("Duplicate") { val copy = studio.duplicateProject(project.projectId); info("Duplicated ${copy.name}"); showProjects() })
            row.addView(button("Delete") { confirm("Delete ${project.name}?") { studio.deleteProject(project.projectId); showProjects() } })
            layout.addView(row)
        }
        layout.addView(TextView(this).apply { text = "AI backends: unavailable/not installed. GLB, projects, scenes, and references work offline."; setPadding(0, 24, 0, 0) })
    }

    private fun showProject(project: ProjectRecord) {
        currentProject = studio.openProject(project.projectId)
        val active = requireNotNull(currentProject)
        val layout = base("Project · ${active.name}")
        preview = AvatarView(this)
        currentAsset?.let { preview.loadGlb(it.glb) }
        layout.addView(preview, 1, LinearLayout.LayoutParams(-1, 420))
        layout.addView(button("Back to projects") { showProjects() })
        layout.addView(button("Rename project") { prompt("Project name", active.name) { showProject(studio.renameProject(active.projectId, it)) } })
        layout.addView(TextView(this).apply { text = "Description: ${active.description}\nProject ID: ${active.projectId}" })
        layout.addView(sectionTitle("Scenes"))
        layout.addView(button("Create scene") { prompt("Scene name", "Scene") { studio.createScene(active.projectId, it); showProject(active) } })
        studio.listScenes(active.projectId).forEach { scene ->
            layout.addView(button("Open scene · ${scene.name}") { showScene(scene) })
            layout.addView(button("Duplicate scene · ${scene.name}") { studio.duplicateScene(scene.sceneId); showProject(active) })
            layout.addView(button("Delete scene · ${scene.name}") { confirm("Delete ${scene.name}?") { studio.deleteScene(scene.sceneId); showProject(active) } })
        }
        layout.addView(sectionTitle("References"))
        layout.addView(button("Import reference file") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, REQUEST_REFERENCE) })
        val refs = studio.listReferences(active.projectId)
        if (refs.isEmpty()) layout.addView(TextView(this).apply { text = "No references imported." })
        refs.forEach { reference -> addReferenceRow(layout, reference, active) }
        layout.addView(sectionTitle("Avatar assets"))
        layout.addView(button("Import GLB") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "model/gltf-binary"; addCategory(Intent.CATEGORY_OPENABLE) }, REQUEST_GLB) })
        val exportButton = button("Export currently loaded GLB") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "model/gltf-binary"; putExtra(Intent.EXTRA_TITLE, "avatar.glb") }, REQUEST_EXPORT) }
        exportButton.isEnabled = currentAsset != null
        layout.addView(exportButton)
        layout.addView(TextView(this).apply { text = "Scene-to-render attachment is not yet implemented; no fake attach action is shown." })
        adultModeButton = button(if (adultSettings.enabled) "Adult mode: ON (tap to disable)" else "Adult mode: OFF (safe mode)") { toggleAdultMode(); showProject(active) }
        layout.addView(adultModeButton)
    }

    private fun showScene(scene: SceneRecord) {
        currentScene = studio.loadScene(scene.sceneId)
        val active = requireNotNull(currentScene)
        val layout = base("Scene · ${active.name}")
        layout.addView(button("Back to project") { studio.openProject(active.projectId)?.let(::showProject) })
        layout.addView(button("Rename scene") { prompt("Scene name", active.name) { showScene(studio.saveScene(active.projectId, it, active.sceneJson, active.sceneId)) } })
        layout.addView(button("Save scene") { studio.saveScene(active.projectId, active.name, active.sceneJson, active.sceneId); info("Scene saved") })
        layout.addView(button("Delete scene") { confirm("Delete ${active.name}?") { studio.deleteScene(active.sceneId); studio.openProject(active.projectId)?.let(::showProject) } })
        layout.addView(TextView(this).apply { text = "Persisted scene data:\n${active.sceneJson}\n\nScene contents are metadata-only until renderer relationships are implemented. Missing assets are not silently treated as renderable." })
    }

    private fun addReferenceRow(layout: LinearLayout, reference: ReferenceRecord, project: ProjectRecord) {
        layout.addView(TextView(this).apply { text = "${reference.originalName} · ${reference.category}\n${reference.byteSize} bytes · ${reference.previewStatus}" })
        if (reference.category == "TEXT" || reference.category == "CODE") layout.addView(button("Open text") { showText(reference) })
        layout.addView(button("Delete reference") { confirm("Delete ${reference.originalName}?") { studio.deleteReference(reference.referenceId); showProject(project) } })
    }

    private fun showText(reference: ReferenceRecord) {
        val text = studio.readText(reference.referenceId)
        AlertDialog.Builder(this).setTitle(reference.originalName).setMessage(text).setPositiveButton("Close", null).show()
    }

    private fun sectionTitle(text: String) = TextView(this).apply { this.text = text; textSize = 18f; setPadding(0, 20, 0, 8) }
    private fun confirm(message: String, action: () -> Unit) { AlertDialog.Builder(this).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ -> action() }.show() }

    private fun toggleAdultMode() {
        if (adultSettings.enabled) adultSettings.disable()
        else AlertDialog.Builder(this).setTitle("Enable adult mode").setMessage("Optional, local setting. Confirm you are 18+. This does not prove identity or anyone's actual age. Provider capability is still checked.").setNegativeButton("Cancel", null).setPositiveButton("I am 18+") { _, _ -> adultSettings.enableAfterExplicit18PlusConfirmation(true) }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        runCatching {
            when (requestCode) {
                REQUEST_REFERENCE -> { val project = requireNotNull(currentProject); val name = data.data!!.lastPathSegment ?: "reference"; studio.importReference(project.projectId, data.data!!, name.substringAfterLast('/')); showProject(project) }
                REQUEST_GLB -> { val asset = engine.importGlb(data.data!!); currentAsset = asset; currentProject?.let(::showProject); info("Loaded and validated ${asset.metadata.id}") }
                REQUEST_EXPORT -> { val asset = requireNotNull(currentAsset); engine.exportGlb(asset.metadata.id, data.data!!); info("Exported validated GLB") }
            }
        }.onFailure { showError(it) }
    }

    companion object { private const val REQUEST_REFERENCE = 44; private const val REQUEST_GLB = 42; private const val REQUEST_EXPORT = 43 }
}
