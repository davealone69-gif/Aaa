package com.example.avatar.sdk.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

class ModelManagerTest {
    @Test fun installsAndVerifiesLocalModel() {
        val root = Files.createTempDirectory("models").toFile(); val source = File(root, "source.bin").also { it.writeBytes("model-bytes".toByteArray()) }
        val descriptor = ModelDescriptor("demo-model", "1", "test", source.toURI().toURL().toString(), sha256(source), source.length(), 1, setOf("TEST"))
        val manager = ModelManager(File(root, "installed")) { Long.MAX_VALUE }
        val installed = manager.install(descriptor)
        assertEquals(ModelInstallState.INSTALLED, installed.state)
        assertEquals(ModelInstallState.INSTALLED, manager.inspect(descriptor).state)
        manager.remove(descriptor); assertTrue(!manager.inspect(descriptor).path.orEmptyFile().exists())
        root.deleteRecursively()
    }

    @Test fun rejectsInsufficientRamBeforeDownload() {
        val root = Files.createTempDirectory("models-low").toFile(); val source = File(root, "source.bin").also { it.writeBytes(byteArrayOf(1)) }
        val descriptor = ModelDescriptor("low-model", "1", "test", source.toURI().toURL().toString(), sha256(source), 1, 999, emptySet())
        assertEquals(ModelInstallState.INSUFFICIENT_RESOURCES, ModelManager(File(root, "installed")) { 1 }.install(descriptor).state)
        root.deleteRecursively()
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun File?.orEmptyFile() = this ?: File("/does/not/exist")
}
