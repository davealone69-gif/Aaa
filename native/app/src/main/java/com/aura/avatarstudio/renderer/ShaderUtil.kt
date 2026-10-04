package com.aura.avatarstudio.renderer

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Shared shader compile/link + buffer helpers. */
object ShaderUtil {

    fun buildProgram(vertexSource: String, fragmentSource: String, tag: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource, "$tag.vs")
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "$tag.fs")
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("$tag link failed: $log")
        }
        return program
    }

    fun compile(type: Int, source: String, tag: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("$tag compile failed: $log")
        }
        return shader
    }

    fun floatBuffer(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data)
        buffer.position(0)  // returns Buffer on the Android stubs — ignore
        return buffer
    }
}
