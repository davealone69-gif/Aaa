package android.content.res

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Minimal JVM stub: serves files from the working directory. */
class AssetManager {
    fun open(name: String): InputStream = FileInputStream(File(name))
}
