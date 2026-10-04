package com.example.avatar.sdk.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbValidatorTest {
    @Test fun rejectsMissingFile() { assertThrows(GlbValidationException::class.java) { GlbValidator.validate(File("/does/not/exist.glb")) } }

    @Test fun acceptsMinimalValidGlb() {
        val file = File.createTempFile("valid", ".glb")
        file.writeBytes(makeGlb("{}"))
        try { val result = GlbValidator.validate(file); assertEquals(file.length().toInt(), result.totalLength); assertEquals(4, result.jsonLength) } finally { file.delete() }
    }

    @Test fun rejectsInvalidMagic() {
        val file = File.createTempFile("bad-magic", ".glb"); file.writeBytes(makeGlb("{}").also { it[0] = 0 })
        try { assertThrows(GlbValidationException::class.java) { GlbValidator.validate(file) } } finally { file.delete() }
    }

    @Test fun rejectsDeclaredLengthMismatch() {
        val bytes = makeGlb("{}"); val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); buffer.putInt(8, bytes.size + 4)
        val file = File.createTempFile("bad-length", ".glb"); file.writeBytes(bytes)
        try { assertThrows(GlbValidationException::class.java) { GlbValidator.validate(file) } } finally { file.delete() }
    }

    private fun makeGlb(json: String): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8); val padded = jsonBytes + ByteArray((4 - jsonBytes.size % 4) % 4) { 0x20 }
        val total = 12 + 8 + padded.size
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46546C67).putInt(2).putInt(total).putInt(padded.size).putInt(0x4E4F534A).put(padded)
        return b.array()
    }
}
