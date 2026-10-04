package com.example.avatar.sdk.render

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.io.File
import java.nio.ByteBuffer

/** Real-time Filament surface. It never substitutes a 2-D preview for a GLB. */
class AvatarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs), Choreographer.FrameCallback {
    private val engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView().apply { this.scene = scene }
    private val camera = engine.createCamera(engine.entityManager.create()).also {
        it.setProjection(45.0, 1.0, 1000.0, 0.1, com.google.android.filament.Camera.Fov.VERTICAL)
        it.lookAt(0.0, 1.0, 3.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)
    }
    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, engine.entityManager)
    private val resourceLoader = ResourceLoader(engine)
    private val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { renderCallback = Callback() }
    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null

    init { view.camera = camera; helper.attachTo(this); isFocusable = true }

    fun loadGlb(file: File) {
        require(file.isFile) { "GLB does not exist: ${file.absolutePath}" }
        asset?.let { scene.removeEntities(it.entities); assetLoader.destroyAsset(it) }
        val loaded = assetLoader.createAsset(ByteBuffer.wrap(file.readBytes())) ?: error("Filament rejected GLB")
        resourceLoader.loadResources(loaded)
        scene.addEntities(loaded.entities)
        asset = loaded
    }

    fun loadGlb(uri: Uri) = loadGlb(File(requireNotNull(uri.path)))

    override fun doFrame(frameTimeNanos: Long) {
        val chain = swapChain
        if (chain != null && renderer.beginFrame(chain, frameTimeNanos)) { renderer.render(view); renderer.endFrame() }
        if (swapChain != null) Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        helper.detach(); swapChain?.let { engine.destroySwapChain(it) }; swapChain = null
        asset?.let { assetLoader.destroyAsset(it) }; resourceLoader.destroy(); assetLoader.destroy(); materialProvider.destroy()
        engine.destroyView(view); engine.destroyScene(scene); engine.destroyRenderer(renderer); engine.destroy()
        super.onDetachedFromWindow()
    }

    private inner class Callback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
            Choreographer.getInstance().removeFrameCallback(this@AvatarView)
            Choreographer.getInstance().postFrameCallback(this@AvatarView)
        }
        override fun onDetachedFromSurface() {
            swapChain?.let { engine.destroySwapChain(it) }; swapChain = null
            Choreographer.getInstance().removeFrameCallback(this@AvatarView)
        }
        override fun onResized(width: Int, height: Int) { view.viewport = Viewport(0, 0, width, height) }
    }
}
