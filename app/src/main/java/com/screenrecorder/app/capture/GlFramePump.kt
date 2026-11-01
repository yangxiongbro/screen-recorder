package com.screenrecorder.app.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sits between the [android.hardware.display.VirtualDisplay] and the encoder and re-times frames.
 *
 * MediaProjection mirrors the display at its refresh rate (60/90/120 Hz) and only produces a frame
 * when something on screen changes, which makes the direct surface-to-encoder path a
 * variable-frame-rate stream that is often *faster* than the configured frame rate. This pump
 * samples the newest mirrored frame on a fixed 1/fps cadence and draws it into the encoder, so the
 * recorded file always has exactly the requested frame rate (duplicating the previous frame when
 * the screen did not change).
 */
internal class GlFramePump(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val onError: (Throwable) -> Unit,
) {

    private val frameIntervalNs = 1_000_000_000L / frameRate.coerceAtLeast(1)
    private val surfaceReady = CountDownLatch(1)

    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var error: Throwable? = null

    @Volatile
    private var hasFrame = false

    private lateinit var surfaceTexture: SurfaceTexture

    private var inputSurface: Surface? = null

    // EGL / GL state, only touched from the render thread.
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var textureId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var texMatrixHandle = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_TEX_COORDS)
            position(0)
        }

    /** Starts the pump and returns the surface that must be handed to the VirtualDisplay. */
    fun start(): Surface {
        if (running) return requireNotNull(inputSurface)
        running = true
        thread = Thread({ renderLoop() }, "GlFramePump").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        surfaceReady.await(5, TimeUnit.SECONDS)
        error?.let { throw IllegalStateException("帧率转换器初始化失败", it) }
        return inputSurface ?: throw IllegalStateException("帧率转换器初始化超时")
    }

    fun setPaused(value: Boolean) {
        paused = value
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.let { runCatching { it.join(2_000L) } }
        thread = null
    }

    private fun renderLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            initEgl()
            initGl()

            val texture = SurfaceTexture(textureId)
            texture.setDefaultBufferSize(width, height)
            texture.setOnFrameAvailableListener(
                { hasFrame = true },
                Handler(Looper.getMainLooper()),
            )
            surfaceTexture = texture
            inputSurface = Surface(texture)
            surfaceReady.countDown()

            drawLoop(texture)
        } catch (t: Throwable) {
            Log.e(TAG, "frame pump failed", t)
            error = t
            onError(t)
        } finally {
            surfaceReady.countDown()
            releaseGl()
        }
    }

    private fun drawLoop(texture: SurfaceTexture) {
        val texMatrix = FloatArray(16)
        var nextFrameNs = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            if (now < nextFrameNs) {
                sleepFor(nextFrameNs - now)
                continue
            }
            // Do not encode a black frame before the first mirrored frame has arrived.
            if (paused || !hasFrame) {
                sleepFor(frameIntervalNs)
                nextFrameNs = System.nanoTime() + frameIntervalNs
                continue
            }

            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            drawFrame(texMatrix)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, now)
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                throw IllegalStateException("eglSwapBuffers failed: ${EGL14.eglGetError()}")
            }

            val planned = nextFrameNs + frameIntervalNs
            nextFrameNs = if (planned < now) now + frameIntervalNs else planned
        }
    }

    private fun sleepFor(nanos: Long) {
        if (nanos <= 0L) return
        val millis = (nanos / 1_000_000L).coerceIn(1L, 4L)
        runCatching { Thread.sleep(millis) }
    }

    // region EGL / GL

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, configCount, 0)) {
            "eglChooseConfig failed"
        }
        val config = configs[0] ?: throw IllegalStateException("no EGL config")
        eglConfig = config

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun initGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun drawFrame(texMatrix: FloatArray) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun releaseGl() {
        inputSurface?.let { runCatching { it.release() } }
        inputSurface = null
        if (this::surfaceTexture.isInitialized) {
            runCatching { surfaceTexture.release() }
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertexShader)
        GLES20.glAttachShader(id, fragmentShader)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("program link failed: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return id
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    // endregion

    private companion object {
        const val TAG = "GlFramePump"

        /** `EGL_RECORDABLE_ANDROID`, missing from the public [EGL14] constants. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
