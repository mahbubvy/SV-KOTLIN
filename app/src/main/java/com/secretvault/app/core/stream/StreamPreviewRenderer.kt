package com.secretvault.app.core.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamPreviewRenderer(private val onError: (Throwable) -> Unit) : SurfaceProcessor, Closeable {
    private val thread = HandlerThread("VaultStreamGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { task -> check(handler.post(task)) { "Preview renderer ended" } }
    val effect = object : CameraEffect(PREVIEW, executor, this, { onError(it) }) {}
    private val closed = AtomicBoolean(false)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: android.opengl.EGLConfig? = null
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val inputs = mutableListOf<Input>()
    private val outputs = linkedMapOf<SurfaceOutput, EGLSurface>()
    private var current: Input? = null
    private var nativeOutput: Pair<EGLSurface, Size>? = null
    private var encoderWindow = EGL14.EGL_NO_SURFACE
    private var encoderRotation = 0
    private var viewportAspect = 1f
    private var lastEncodedNs = 0L
    private val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val coordinates = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0)
    }
    private data class Input(val textureId: Int, val texture: SurfaceTexture, val surface: Surface)
    class NativeInput internal constructor(val surface: Surface, private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    init {
        try { onGl {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val choices = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, 0x3142, 1, EGL14.EGL_NONE), 0, choices, 0, 1, count, 0) && count[0] > 0)
            config = choices[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            makeCurrent(pbuffer)
            fun shader(type: Int, source: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
                val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) { val error = GLES20.glGetShaderInfoLog(id); GLES20.glDeleteShader(id); error(error) }
                return id
            }
            val vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec4 position; attribute vec4 uv; uniform mat4 textureMatrix; uniform mat4 positionMatrix; varying vec2 tex; void main(){gl_Position=positionMatrix*position; tex=(textureMatrix*uv).xy;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES camera; varying vec2 tex; void main(){gl_FragColor=texture2D(camera,tex);}")
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        } } catch (error: Exception) { close(); throw error }
    }

    private fun <T> onGl(action: () -> T): T {
        val result = CompletableFuture<T>()
        check(handler.post { try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) } })
        return result.get(5, TimeUnit.SECONDS)
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (closed.get()) { request.willNotProvideSurface(); return }
        try {
            val input = createInput(request.resolution)
            current = input
            request.provideSurface(input.surface, executor) { releaseInput(input) }
        } catch (error: Exception) { request.willNotProvideSurface(); onError(error) }
    }

    private fun createInput(size: Size): Input {
        makeCurrent(pbuffer)
        val id = IntArray(1); GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val texture = SurfaceTexture(id[0]).apply { setDefaultBufferSize(size.width, size.height) }
        val input = Input(id[0], texture, Surface(texture))
        inputs.add(input)
        texture.setOnFrameAvailableListener({ if (!closed.get() && current === input) {
            try { render(input) } catch (error: Exception) { onError(error); close() }
        } }, handler)
        return input
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (closed.get()) { output.close(); return }
        try {
            val surface = output.getSurface(executor) {
                outputs.remove(output)?.let { destroyWindow(it) }; output.close()
            }
            outputs[output] = createWindow(surface)
        } catch (error: Exception) { output.close(); onError(error) }
    }

    fun nativePreview(preview: Surface, inputSize: Size, outputSize: Size): NativeInput = onGl {
        check(!closed.get() && nativeOutput == null && inputs.isEmpty())
        val input = createInput(inputSize)
        try {
            nativeOutput = createWindow(preview) to outputSize; current = input
            NativeInput(input.surface) { onGl {
                nativeOutput?.let { destroyWindow(it.first) }; nativeOutput = null
                releaseInput(input)
            } }
        } catch (error: Exception) { releaseInput(input); throw error }
    }

    fun attachEncoder(surface: Surface, rotation: Int, viewAspect: Float) = onGl {
        check(!closed.get() && encoderWindow == EGL14.EGL_NO_SURFACE)
        require(rotation == 0 || rotation == 90)
        require(viewAspect.isFinite() && viewAspect > 0f)
        encoderWindow = createWindow(surface); encoderRotation = rotation
        viewportAspect = viewAspect; lastEncodedNs = 0L
    }

    fun detachEncoder() = onGl {
        destroyWindow(encoderWindow); encoderWindow = EGL14.EGL_NO_SURFACE
    }

    private fun render(input: Input) {
        makeCurrent(pbuffer); input.texture.updateTexImage()
        val original = FloatArray(16); input.texture.getTransformMatrix(original)
        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        var encodedThisFrame = false
        fun renderOutput(window: EGLSurface, size: Size, transform: FloatArray) {
            draw(window, size, input.textureId, transform, identity, input.texture.timestamp)
            val now = input.texture.timestamp
            if (!encodedThisFrame && encoderWindow != EGL14.EGL_NO_SURFACE && now - lastEncodedNs >= 32_000_000L) {
                val aspect = size.width.toFloat() / size.height
                val crop = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
                Matrix.translateM(crop, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(crop, 0, minOf(1f, viewportAspect / aspect), minOf(1f, aspect / viewportAspect), 1f)
                Matrix.translateM(crop, 0, -0.5f, -0.5f, 0f)
                val streamTransform = FloatArray(16); Matrix.multiplyMM(streamTransform, 0, transform, 0, crop, 0)
                val targetAspect = if (encoderRotation == 90) 720f / 1280 else 1280f / 720
                val position = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
                Matrix.rotateM(position, 0, encoderRotation.toFloat(), 0f, 0f, 1f)
                Matrix.scaleM(position, 0, minOf(1f, viewportAspect / targetAspect), minOf(1f, targetAspect / viewportAspect), 1f)
                draw(encoderWindow, Size(1280, 720), input.textureId, streamTransform, position, now)
                lastEncodedNs = now; encodedThisFrame = true
            }
        }
        for ((output, window) in outputs) {
            val transform = FloatArray(16); output.updateTransformMatrix(transform, original)
            renderOutput(window, output.size, transform)
        }
        nativeOutput?.let { renderOutput(it.first, it.second, original) }
    }

    private fun draw(window: EGLSurface, size: Size, texture: Int, transform: FloatArray, position: FloatArray, timestamp: Long) {
        makeCurrent(window); GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val vertex = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        GLES20.glEnableVertexAttribArray(vertex); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(vertex, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "textureMatrix"), 1, false, transform, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "positionMatrix"), 1, false, position, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "camera"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Preview GPU draw failed" }
        check(EGLExt.eglPresentationTimeANDROID(display, window, timestamp))
        check(EGL14.eglSwapBuffers(display, window)) { "Preview surface stopped accepting frames" }
    }

    private fun createWindow(surface: Surface): EGLSurface = EGL14.eglCreateWindowSurface(display, config, surface,
        intArrayOf(EGL14.EGL_NONE), 0).also { check(it != EGL14.EGL_NO_SURFACE) { "Preview surface unavailable" } }
    private fun makeCurrent(surface: EGLSurface) { check(EGL14.eglMakeCurrent(display, surface, surface, context)) }
    private fun destroyWindow(window: EGLSurface) {
        if (window != EGL14.EGL_NO_SURFACE) { makeCurrent(pbuffer); EGL14.eglDestroySurface(display, window) }
    }
    private fun releaseInput(input: Input) {
        if (!inputs.remove(input)) return
        if (current === input) current = null
        input.texture.setOnFrameAvailableListener(null); input.surface.release(); input.texture.release()
        makeCurrent(pbuffer); GLES20.glDeleteTextures(1, intArrayOf(input.textureId), 0)
        releaseIfFinished()
    }
    private fun releaseIfFinished() {
        if (!closed.get() || inputs.isNotEmpty()) return
        if (context != EGL14.EGL_NO_CONTEXT) {
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer); EGL14.eglDestroyContext(display, context)
        }
        EGL14.eglTerminate(display); EGL14.eglReleaseThread(); thread.quitSafely()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            destroyWindow(encoderWindow); encoderWindow = EGL14.EGL_NO_SURFACE
            nativeOutput?.let { destroyWindow(it.first) }; nativeOutput = null
            outputs.forEach { (output, window) -> destroyWindow(window); output.close() }; outputs.clear()
            releaseIfFinished()
        }
    }
}
