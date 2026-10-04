package com.aura.avatarstudio

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.aura.avatarstudio.renderer.GltfAvatarLoader
import com.aura.avatarstudio.renderer.HdAvatarRenderer

/**
 * Touch-wired GL view. Loads the avatar from assets once the GL context
 * is live (renderer.onSurfaceCreated), then re-applies it if the context
 * is recreated (e.g. activity pause/resume with preserveEGLContextOnPause
 * disabled on some devices).
 */
class GltfAvatarView(
    private val context: Context,
    private val assetName: String
) : GLSurfaceView(context) {

    private val renderer = HdAvatarRenderer(context)
    private var loaded = false

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 8)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY

        queueEvent {
            if (!loaded) {
                loaded = true
                val loader = GltfAvatarLoader(context)
                renderer.setAvatar(
                    loader.loadFromAssets(assetName)
                )
            }
        }
    }

    /** Re-apply the avatar (call from onSurfaceCreated if needed). */
    fun reloadAvatar() {
        queueEvent {
            val loader = GltfAvatarLoader(context)
            renderer.setAvatar(loader.loadFromAssets(assetName))
        }
    }

    // ---- touch controls --------------------------------------------------
    private var lastX = 0f
    private var lastY = 0f
    private var baseDistance = 1f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (baseDistance > 0f && dist > 0f) {
                        renderer.zoomCamera(dist / baseDistance)
                    }
                    baseDistance = dist
                    lastX = event.x
                    lastY = event.y
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    renderer.rotateCamera(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val dx = event.getX(0) - event.getX(1)
                val dy = event.getY(0) - event.getY(1)
                baseDistance = kotlin.math.sqrt(dx * dx + dy * dy)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                baseDistance = 0f
            }
        }
        return true
    }
}
