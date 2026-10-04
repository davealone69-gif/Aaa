package android.content

/** Minimal JVM stub for headless tests. */
open class Context {
    val assets: android.content.res.AssetManager = android.content.res.AssetManager()
    val resources: android.content.res.Resources = android.content.res.Resources()
}
