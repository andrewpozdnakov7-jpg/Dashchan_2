package com.mishiranu.dashchan.media;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Worker-owned offscreen consumer. No View, window, network or audio focus is involved. */
final class VideoFramePreparation implements AutoCloseable {
	private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
	private EGLContext context = EGL14.EGL_NO_CONTEXT;
	private EGLSurface output = EGL14.EGL_NO_SURFACE;
	private SurfaceTexture texture;
	private int program;
	private final int width, height;
	private final Semaphore available = new Semaphore(0);

	VideoFramePreparation(int videoWidth, int videoHeight) throws IOException {
		float scale = Math.min(1f, 640f / Math.max(videoWidth, videoHeight));
		width = Math.max(1, Math.round(videoWidth * scale));
		height = Math.max(1, Math.round(videoHeight * scale));
		try {
			display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
			if (display.equals(EGL14.EGL_NO_DISPLAY) || !EGL14.eglInitialize(display, new int[2], 0, new int[2], 0)) {
				throw new IOException("Preparation EGL display unavailable");
			}
			int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
					EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
					EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
			EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
			if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] < 1) {
				throw new IOException("Preparation EGL config unavailable");
			}
			context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
					new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
			output = EGL14.eglCreatePbufferSurface(display, configs[0],
					new int[] {EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE}, 0);
			if (context.equals(EGL14.EGL_NO_CONTEXT) || output.equals(EGL14.EGL_NO_SURFACE)
					|| !EGL14.eglMakeCurrent(display, output, output, context)) {
				throw new IOException("Preparation EGL context unavailable");
			}
			int[] name = new int[1]; GLES20.glGenTextures(1, name, 0);
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, name[0]);
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
			texture = new SurfaceTexture(name[0]);
			texture.setDefaultBufferSize(videoWidth, videoHeight);
			// Only signal on main; updateTexImage/readback stay on this EGL worker.
			texture.setOnFrameAvailableListener(t -> available.release(), new Handler(Looper.getMainLooper()));
		} catch (IOException | RuntimeException | OutOfMemoryError e) { close(); throw e; }
	}

	/** Surface wrapper ownership transfers to VideoPlayer's generation-aware request queue. */
	Surface createSurface() { return new Surface(texture); }

	Bitmap awaitFrame(BooleanSupplier cancelled) throws IOException, InterruptedException {
		long deadline = SystemClock.elapsedRealtime() + 2500L;
		while (!cancelled.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
			if (available.tryAcquire(40, TimeUnit.MILLISECONDS)) {
				if (cancelled.getAsBoolean()) return null;
				texture.updateTexImage();
				return capture();
			}
		}
		return null;
	}

	SurfaceTexture takeTexture() {
		texture.setOnFrameAvailableListener(null);
		// TextureView may only adopt a texture detached from its previous GL context.
		texture.detachFromGLContext();
		SurfaceTexture result = texture; texture = null; return result;
	}

	private Bitmap capture() throws IOException {
		int vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPosition; attribute vec2 aUv;"
				+ "uniform mat4 uTransform; varying vec2 vUv; void main() {"
				+ "gl_Position=vec4(aPosition,0.0,1.0); vUv=(uTransform*vec4(aUv,0.0,1.0)).xy; }");
		int fragment = 0;
		try {
			fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\n"
					+ "precision mediump float; uniform samplerExternalOES uTexture; varying vec2 vUv;"
					+ "void main() { gl_FragColor=texture2D(uTexture,vUv); }");
			program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment);
			GLES20.glLinkProgram(program); int[] status = new int[1];
			GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
			if (status[0] == 0) throw new IOException("Preparation shader link failed");
		} finally { GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment); }
		GLES20.glUseProgram(program); GLES20.glViewport(0, 0, width, height);
		float[] transform = new float[16]; texture.getTransformMatrix(transform);
		GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0);
		GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
		FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		quad.put(new float[] {-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1});
		int position = GLES20.glGetAttribLocation(program, "aPosition"), uv = GLES20.glGetAttribLocation(program, "aUv");
		quad.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad);
		quad.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad);
		GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
		GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
		if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw new IOException("Preparation frame readback failed");
		// Explicit RGBA -> ARGB conversion, including GL's bottom-up rows, independent of host endian.
		int[] colors = new int[width * height];
		for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
			int source = (y * width + x) * 4;
			colors[(height - 1 - y) * width + x] = ((rgba.get(source + 3) & 255) << 24)
					| ((rgba.get(source) & 255) << 16) | ((rgba.get(source + 1) & 255) << 8) | (rgba.get(source + 2) & 255);
		}
		return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
	}

	private static int shader(int type, String source) throws IOException {
		int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
		int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0) { GLES20.glDeleteShader(shader); throw new IOException("Preparation shader compile failed"); }
		return shader;
	}

	@Override public void close() {
		if (texture != null) { texture.setOnFrameAvailableListener(null); texture.release(); texture = null; }
		if (!display.equals(EGL14.EGL_NO_DISPLAY)) {
			if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
			EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
			if (!output.equals(EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display, output);
			if (!context.equals(EGL14.EGL_NO_CONTEXT)) EGL14.eglDestroyContext(display, context);
			EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
			display = EGL14.EGL_NO_DISPLAY; output = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT;
		}
	}
}
