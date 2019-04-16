/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpu;

import com.google.inject.Provides;
import com.jogamp.nativewindow.awt.AWTGraphicsConfiguration;
import com.jogamp.nativewindow.awt.JAWTWindow;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLDrawable;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLProfile;
import jogamp.nativewindow.SurfaceScaleUtils;
import jogamp.nativewindow.jawt.x11.X11JAWTWindow;
import jogamp.newt.awt.NewtFactoryAWT;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWT;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.Function;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NodeCache;
import net.runelite.api.Perspective;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import static net.runelite.client.plugins.gpu.GLUtil.*;
import net.runelite.client.plugins.gpu.config.AntiAliasingMode;
import net.runelite.client.plugins.gpu.template.Template;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.OSType;

@PluginDescriptor(
	name = "GPU",
	description = "Utilizes the GPU",
	enabledByDefault = false,
	tags = {"fog", "draw distance"}
)
@Slf4j
public class GpuPlugin extends Plugin implements DrawCallbacks
{
	// This is the maximum number of triangles the compute shaders support
	private static final int MAX_TRIANGLE = 4096;
	private static final int SMALL_TRIANGLE_COUNT = 512;
	private static final int FLAG_SCENE_BUFFER = Integer.MIN_VALUE;
	static final int MAX_DISTANCE = 90;
	static final int MAX_FOG_DEPTH = 100;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GpuPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	private Canvas canvas;
/**	private JAWTWindow jawtWindow; **/
	private GL43 gl;
/**	private GLContext glContext;
 *  private GLDrawable glDrawable; **/

	private int glProgram;
	private int glVertexShader;
	private int glGeomShader;
	private int glFragmentShader;

	private int glComputeProgram;
	private int glComputeShader;

	private int glSmallComputeProgram;
	private int glSmallComputeShader;

	private int glUnorderedComputeProgram;
	private int glUnorderedComputeShader;

	private int vaoHandle;

	private int interfaceTexture;

	private int glUiProgram;
	private int glUiVertexShader;
	private int glUiFragmentShader;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboSceneHandle;
	private int texSceneHandle;
	private int rboSceneHandle;

	// scene vertex buffer id
	private int bufferId;
	// scene uv buffer id
	private int uvBufferId;

	private int textureArrayId;

	private int uniformBufferId;
	private final IntBuffer uniformBuffer = GpuIntBuffer.allocateDirect(5 + 3 + 2048 * 4);
	private final float[] textureOffsets = new float[128];

	private GpuIntBuffer vertexBuffer;
	private GpuFloatBuffer uvBuffer;

	private GpuIntBuffer modelBufferUnordered;
	private GpuIntBuffer modelBufferSmall;
	private GpuIntBuffer modelBuffer;

	private int unorderedModels;

	/**
	 * number of models in small buffer
	 */
	private int smallModels;

	/**
	 * number of models in large buffer
	 */
	private int largeModels;

	/**
	 * offset in the target buffer for model
	 */
	private int targetBufferOffset;

	/**
	 * offset into the temporary scene vertex buffer
	 */
	private int tempOffset;

	/**
	 * offset into the temporary scene uv buffer
	 */
	private int tempUvOffset;

	private int lastViewportWidth;
	private int lastViewportHeight;
	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;

	private int centerX;
	private int centerY;

	// Uniforms
	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniDrawDistance;
	private int uniProjectionMatrix;
	private int uniBrightness;
	private int uniTex;
	private int uniTextures;
	private int uniTextureOffsets;
	private int uniBlockSmall;
	private int uniBlockLarge;
	private int uniBlockMain;
	private int uniSmoothBanding;

	@Override
	protected void startUp()
	{
		clientThread.invoke(() ->
		{
			try
			{
				bufferId = uvBufferId = uniformBufferId = -1;
				unorderedModels = smallModels = largeModels = 0;

				vertexBuffer = new GpuIntBuffer();
				uvBuffer = new GpuFloatBuffer();

				modelBufferUnordered = new GpuIntBuffer();
				modelBufferSmall = new GpuIntBuffer();
				modelBuffer = new GpuIntBuffer();

				canvas = client.getCanvas();
				canvas.setIgnoreRepaint(true);

				if (log.isDebugEnabled())
				{
					System.setProperty("jogl.debug", "true");
				}

				GL.createCapabilities();

				GLCapabilities glCaps = GL.getCapabilities();
				AWTGraphicsConfiguration config = AWTGraphicsConfiguration.create(canvas.getGraphicsConfiguration(), glCaps, glCaps);

				glfwWindow = NewtFactoryAWT.getNativeWindow(canvas, config);
				canvas.setFocusable(true);

				GLDrawableFactory glDrawableFactory = GLDrawableFactory.getFactory(glProfile);

				glDrawable = glDrawableFactory.createGLDrawable(glfwWindow);
				glDrawable.setRealized(true);

				glContext = glDrawable.createContext(null);
				if (log.isDebugEnabled())
				{
					// Debug config on context needs to be set before .makeCurrent call
					glContext.enableGLDebugMessage(true);
				}

				int res = glContext.makeCurrent();
				if (res == glContext.CONTEXT_NOT_CURRENT)
				{
					throw new RuntimeException("Unable to make context current");
				}

				// Surface needs to be unlocked on X11 window otherwise input is blocked
				if (glfwWindow instanceof X11JAWTWindow && glfwWindow.getLock().isLocked())
				{
					glfwWindow.unlockSurface();
				}

				glfwSwapInterval(0);

				if (log.isDebugEnabled())
				{
					GL11.glEnable(GL43.GL_DEBUG_OUTPUT);

					// Suppress warning messages which flood the log on NVIDIA systems.
					GL43.glDebugMessageControl(GL43.GL_DEBUG_SOURCE_API, GL43.GL_DEBUG_TYPE_OTHER,
						GL43.GL_DEBUG_SEVERITY_NOTIFICATION, 0, false);
				}

				initVao();
				initProgram();
				initInterfaceTexture();
				initUniformBuffer();

				client.setDrawCallbacks(this);
				client.setGpu(true);

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastViewportWidth = lastViewportHeight = lastCanvasWidth = lastCanvasHeight = -1;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = -1;
				lastAntiAliasingMode = null;

				textureArrayId = -1;

				// increase size of model cache for dynamic objects since we are extending scene size
				NodeCache cachedModels2 = client.getCachedModels2();
				cachedModels2.setCapacity(256);
				cachedModels2.setRemainingCapacity(256);
				cachedModels2.reset();

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					uploadScene();
				}
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				try
				{
					pluginManager.setPluginEnabled(this, false);
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex)
				{
					log.error("error stopping plugin", ex);
				}

				shutDown();
			}

		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			client.setGpu(false);
			client.setDrawCallbacks(null);

			if (gl != null)
			{
				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(gl, textureArrayId);
					textureArrayId = -1;
				}

				if (bufferId != -1)
				{
					GLUtil.glDeleteBuffer(gl, bufferId);
					bufferId = -1;
				}

				if (uvBufferId != -1)
				{
					GLUtil.glDeleteBuffer(gl, uvBufferId);
					uvBufferId = -1;
				}

				if (uniformBufferId != -1)
				{
					GLUtil.glDeleteBuffer(gl, uniformBufferId);
					uniformBufferId = -1;
				}

				shutdownInterfaceTexture();
				shutdownProgram();
				shutdownVao();
				shutdownSceneFbo();
			}

			if (glfwWindow != null)
			{
				if (!glfwWindow.getLock().isLocked())
				{
					glfwWindow.lockSurface();
				}

				if (glContext != null)
				{
					glContext.destroy();
				}

				NewtFactoryAWT.destroyNativeWindow(glfwWindow);
			}

			glfwWindow = null;
			gl = null;
			glDrawable = null;
			glContext = null;

			vertexBuffer = null;
			uvBuffer = null;

			modelBufferSmall = null;
			modelBuffer = null;
			modelBufferUnordered = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	@Provides
	GpuPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpuPluginConfig.class);
	}

	private void initProgram() throws ShaderException
	{
		glProgram = GL20.glCreateProgram();
		glVertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
		glGeomShader = GL20.glCreateShader(GL32.GL_GEOMETRY_SHADER);
		glFragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);

		final String glVersionHeader;

		if (OSType.getOSType() == OSType.Linux)
		{
			glVersionHeader =
				"#version 420\n" +
				"#extension GL_ARB_compute_shader : require\n" +
				"#extension GL_ARB_shader_storage_buffer_object : require\n";
		}
		else
		{
			glVersionHeader = "#version 430\n";
		}

		Function<String, String> resourceLoader = (s) ->
		{
			if (s.endsWith(".glsl"))
			{
				return inputStreamToString(getClass().getResourceAsStream(s));
			}

			if (s.equals("version_header"))
			{
				return glVersionHeader;
			}

			return "";
		};

		Template template = new Template(resourceLoader);
		String source = template.process(resourceLoader.apply("geom.glsl"));

		template = new Template(resourceLoader);
		String vertSource = template.process(resourceLoader.apply("vert.glsl"));

		template = new Template(resourceLoader);
		String fragSource = template.process(resourceLoader.apply("frag.glsl"));

		GLUtil.loadShaders(gl, glProgram, glVertexShader, glGeomShader, glFragmentShader,
			vertSource,
			source,
			fragSource);

		glComputeProgram = GL20.glCreateProgram();
		glComputeShader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
		template = new Template(resourceLoader);
		source = template.process(resourceLoader.apply("comp.glsl"));
		GLUtil.loadComputeShader(gl, glComputeProgram, glComputeShader, source);

		glSmallComputeProgram = GL20.glCreateProgram();
		glSmallComputeShader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
		template = new Template(resourceLoader);
		source = template.process(resourceLoader.apply("comp_small.glsl"));
		GLUtil.loadComputeShader(gl, glSmallComputeProgram, glSmallComputeShader, source);

		glUnorderedComputeProgram = GL20.glCreateProgram();
		glUnorderedComputeShader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
		template = new Template(resourceLoader);
		source = template.process(resourceLoader.apply("comp_unordered.glsl"));
		GLUtil.loadComputeShader(gl, glUnorderedComputeProgram, glUnorderedComputeShader, source);

		glUiProgram = GL20.glCreateProgram();
		glUiVertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
		glUiFragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
		GLUtil.loadShaders(gl, glUiProgram, glUiVertexShader, -1, glUiFragmentShader,
			inputStreamToString(getClass().getResourceAsStream("vertui.glsl")),
			null,
			inputStreamToString(getClass().getResourceAsStream("fragui.glsl")));

		initUniforms();
	}

	private void initUniforms()
	{
		uniProjectionMatrix = GL20.glGetUniformLocation(glProgram, "projectionMatrix");
		uniBrightness = GL20.glGetUniformLocation(glProgram, "brightness");
		uniSmoothBanding = GL20.glGetUniformLocation(glProgram, "smoothBanding");
		uniUseFog = GL20.glGetUniformLocation(glProgram, "useFog");
		uniFogColor = GL20.glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = GL20.glGetUniformLocation(glProgram, "fogDepth");
		uniDrawDistance = GL20.glGetUniformLocation(glProgram, "drawDistance");

		uniTex = GL20.glGetUniformLocation(glUiProgram, "tex");
		uniTextures = GL20.glGetUniformLocation(glProgram, "textures");
		uniTextureOffsets = GL20.glGetUniformLocation(glProgram, "textureOffsets");

		uniBlockSmall = GL31.glGetUniformBlockIndex(glSmallComputeProgram, "uniforms");
		uniBlockLarge = GL31.glGetUniformBlockIndex(glComputeProgram, "uniforms");
		uniBlockMain = GL31.glGetUniformBlockIndex(glProgram, "uniforms");
	}

	private void shutdownProgram()
	{
		GL20.glDeleteShader(glVertexShader);
		glVertexShader = -1;

		GL20.glDeleteShader(glGeomShader);
		glGeomShader = -1;

		GL20.glDeleteShader(glFragmentShader);
		glFragmentShader = -1;

		GL20.glDeleteProgram(glProgram);
		glProgram = -1;

		///

		GL20.glDeleteShader(glComputeShader);
		glComputeShader = -1;

		GL20.glDeleteProgram(glComputeProgram);
		glComputeProgram = -1;

		GL20.glDeleteShader(glSmallComputeShader);
		glSmallComputeShader = -1;

		GL20.glDeleteProgram(glSmallComputeProgram);
		glSmallComputeProgram = -1;

		GL20.glDeleteShader(glUnorderedComputeShader);
		glUnorderedComputeShader = -1;

		GL20.glDeleteProgram(glUnorderedComputeProgram);
		glUnorderedComputeProgram = -1;

		///

		GL20.glDeleteShader(glUiVertexShader);
		glUiVertexShader = -1;

		GL20.glDeleteShader(glUiFragmentShader);
		glUiFragmentShader = -1;

		GL20.glDeleteProgram(glUiProgram);
		glUiProgram = -1;
	}

	private void initVao()
	{
		// Create VAO
		vaoHandle = glGenVertexArrays(gl);

		// Create UI VAO
		vaoUiHandle = glGenVertexArrays(gl);
		// Create UI buffer
		vboUiHandle = glGenBuffers(gl);
		GL30.glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0.0f, 1.0f, 0f, // top right
			1f, -1f, 0.0f, 1.0f, 1f, // bottom right
			-1f, -1f, 0.0f, 0.0f, 1f, // bottom left
			-1f, 1f, 0.0f, 0.0f, 0f  // top left
		});
		vboUiBuf.rewind();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboUiHandle);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboUiBuf.capacity() * Float.BYTES, GL15.GL_STATIC_DRAW);

		// position attribute
		GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 5 * Float.BYTES, 0);
		GL20.glEnableVertexAttribArray(0);

		// texture coord attribute
		GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		GL20.glEnableVertexAttribArray(1);

		// unbind VBO
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteVertexArrays(gl, vaoHandle);
		vaoHandle = -1;

		glDeleteBuffer(gl, vboUiHandle);
		vboUiHandle = -1;

		glDeleteVertexArrays(gl, vaoUiHandle);
		vaoUiHandle = -1;
	}

	private void initInterfaceTexture()
	{
		interfaceTexture = glGenTexture(gl);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, interfaceTexture);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteTexture(gl, interfaceTexture);
		interfaceTexture = -1;
	}

	private void initUniformBuffer()
	{
		uniformBufferId = glGenBuffers(gl);
		GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniformBufferId);
		uniformBuffer.clear();
		uniformBuffer.put(new int[8]);
		final int[] pad = new int[2];
		for (int i = 0; i < 2048; i++)
		{
			uniformBuffer.put(Perspective.SINE[i]);
			uniformBuffer.put(Perspective.COSINE[i]);
			uniformBuffer.put(pad);
		}
		uniformBuffer.flip();

		GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, uniformBuffer.limit() * Integer.BYTES, GL15.GL_STATIC_DRAW);
		GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
	}

	private void initSceneFbo(int width, int height, int aaSamples)
	{
		// Create and bind the FBO
		fboSceneHandle = glGenFrameBuffer(gl);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboSceneHandle);

		// Create color render buffer
		rboSceneHandle = glGenRenderbuffer(gl);
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rboSceneHandle);
		GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, aaSamples, GL11.GL_RGBA, width, height);
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, rboSceneHandle);

		// Create texture
		texSceneHandle = glGenTexture(gl);
		GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, texSceneHandle);
		GL32.glTexImage2DMultisample(GL32.GL_TEXTURE_2D_MULTISAMPLE, aaSamples, GL11.GL_RGBA, width, height, true);

		// Bind texture
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL32.GL_TEXTURE_2D_MULTISAMPLE, texSceneHandle, 0);

		// Reset
		GL11.glBindTexture(GL32.GL_TEXTURE_2D_MULTISAMPLE, 0);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
	}

	private void shutdownSceneFbo()
	{
		if (texSceneHandle != -1)
		{
			glDeleteTexture(gl, texSceneHandle);
			texSceneHandle = -1;
		}

		if (fboSceneHandle != -1)
		{
			glDeleteFrameBuffer(gl, fboSceneHandle);
			fboSceneHandle = -1;
		}

		if (rboSceneHandle != -1)
		{
			glDeleteRenderbuffers(gl, rboSceneHandle);
			rboSceneHandle = -1;
		}
	}

	private void createProjectionMatrix(float left, float right, float bottom, float top, float near, float far)
	{
		// create a standard orthographic projection
		float tx = -((right + left) / (right - left));
		float ty = -((top + bottom) / (top - bottom));
		float tz = -((far + near) / (far - near));

		GL20.glUseProgram(glProgram);

		float[] matrix = new float[]{
			2 / (right - left), 0, 0, 0,
			0, 2 / (top - bottom), 0, 0,
			0, 0, -2 / (far - near), 0,
			tx, ty, tz, 1
		};
		GL20.glUniformMatrix4fv(uniProjectionMatrix, false, matrix);

		GL20.glUseProgram(0);
	}

	@Override
	public void drawScene(int cameraX, int cameraY, int cameraZ, int cameraPitch, int cameraYaw, int plane)
	{
		centerX = client.getCenterX();
		centerY = client.getCenterY();

		final Scene scene = client.getScene();
		final int drawDistance = Math.max(0, Math.min(MAX_DISTANCE, config.drawDistance()));
		scene.setDrawDistance(drawDistance);
	}

	public void drawScenePaint(int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
							SceneTilePaint paint, int tileZ, int tileX, int tileY,
							int zoom, int centerX, int centerY)
	{
		if (paint.getBufferLen() > 0)
		{
			x = tileX * Perspective.LOCAL_TILE_SIZE;
			y = 0;
			z = tileY * Perspective.LOCAL_TILE_SIZE;

			GpuIntBuffer b = modelBufferUnordered;
			++unorderedModels;

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(paint.getBufferOffset());
			buffer.put(paint.getUvBufferOffset());
			buffer.put(2);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER);
			buffer.put(x).put(y).put(z);

			targetBufferOffset += 2 * 3;
		}
	}

	public void drawSceneModel(int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
							SceneTileModel model, int tileZ, int tileX, int tileY,
							int zoom, int centerX, int centerY)
	{
		if (model.getBufferLen() > 0)
		{
			x = tileX * Perspective.LOCAL_TILE_SIZE;
			y = 0;
			z = tileY * Perspective.LOCAL_TILE_SIZE;

			GpuIntBuffer b = modelBufferUnordered;
			++unorderedModels;

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(model.getBufferOffset());
			buffer.put(model.getUvBufferOffset());
			buffer.put(model.getBufferLen() / 3);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER);
			buffer.put(x).put(y).put(z);

			targetBufferOffset += model.getBufferLen();
		}
	}

	@Override
	public void draw()
	{
		if (glfwWindow.getAWTComponent() != client.getCanvas())
		{
			// We inject code in the game engine mixin to prevent the client from doing canvas replacement,
			// so this should not ever be hit
			log.warn("Canvas invalidated!");
			shutDown();
			startUp();
			return;
		}

		if (client.getGameState() == GameState.LOADING || client.getGameState() == GameState.HOPPING)
		{
			// While the client is loading it doesn't draw
			return;
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		// If the viewport has changed, update the projection matrix
		if (viewportWidth > 0 && viewportHeight > 0 && (viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight))
		{
			createProjectionMatrix(0, viewportWidth, viewportHeight, 0, 0, Constants.SCENE_SIZE * Perspective.LOCAL_TILE_SIZE);
			lastViewportWidth = viewportWidth;
			lastViewportHeight = viewportHeight;
		}

		// Setup anti-aliasing
		final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
		final boolean aaEnabled = antiAliasingMode != AntiAliasingMode.DISABLED;

		if (aaEnabled)
		{
			GL11.glEnable(GL13.GL_MULTISAMPLE);

			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Re-create fbo
			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight
				|| lastAntiAliasingMode != antiAliasingMode)
			{
				shutdownSceneFbo();

				final int maxSamples = glGetInteger(gl, GL30.GL_MAX_SAMPLES);
				final int samples = Math.min(antiAliasingMode.getSamples(), maxSamples);

				initSceneFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples);

				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
			}

			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fboSceneHandle);
		}
		else
		{
			GL11.glDisable(GL13.GL_MULTISAMPLE);
			shutdownSceneFbo();
		}

		lastAntiAliasingMode = antiAliasingMode;

		// Clear scene
		int sky = client.getSkyboxColor();
		GL11.glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

		// Upload buffers
		vertexBuffer.flip();
		uvBuffer.flip();
		modelBuffer.flip();
		modelBufferSmall.flip();
		modelBufferUnordered.flip();

		int bufferId = glGenBuffers(gl); // temporary scene vertex buffer
		int uvBufferId = glGenBuffers(gl); // temporary scene uv buffer
		int modelBufferId = glGenBuffers(gl); // scene model buffer, large
		int modelBufferSmallId = glGenBuffers(gl); // scene model buffer, small
		int modelBufferUnorderedId = glGenBuffers(gl);

		IntBuffer vertexBuffer = this.vertexBuffer.getBuffer();
		FloatBuffer uvBuffer = this.uvBuffer.getBuffer();
		IntBuffer modelBuffer = this.modelBuffer.getBuffer();
		IntBuffer modelBufferSmall = this.modelBufferSmall.getBuffer();
		IntBuffer modelBufferUnordered = this.modelBufferUnordered.getBuffer();

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer.limit() * Integer.BYTES, GL15.GL_STREAM_DRAW);

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, uvBufferId);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, uvBuffer.limit() * Float.BYTES, GL15.GL_STREAM_DRAW);

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, modelBufferId);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, modelBuffer.limit() * Integer.BYTES, GL15.GL_STREAM_DRAW);

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, modelBufferSmallId);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, modelBufferSmall.limit() * Integer.BYTES, GL15.GL_STREAM_DRAW);

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, modelBufferUnorderedId);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, modelBufferUnordered.limit() * Integer.BYTES, GL15.GL_STREAM_DRAW);

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

		// allocate target vertex buffer for compute shaders
		int outBufferId = glGenBuffers(gl);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outBufferId);

		GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
			targetBufferOffset * 16, // each vertex is an ivec4, which is 16 bytes
			GL15.GL_STREAM_DRAW);

		// allocate target uv buffer for compute shaders
		int outUvBufferId = glGenBuffers(gl);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outUvBufferId);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
			targetBufferOffset * 16,
			GL15.GL_STREAM_DRAW);

		// UBO
		GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniformBufferId);
		uniformBuffer.clear();
		uniformBuffer
			.put(client.getCameraYaw())
			.put(client.getCameraPitch())
			.put(centerX)
			.put(centerY)
			.put(client.getScale())
			.put(client.getCameraX2())
			.put(client.getCameraY2())
			.put(client.getCameraZ2());
		uniformBuffer.flip();

		GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, uniformBuffer);
		GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

		// Draw 3d scene
		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureProvider != null && this.bufferId != -1)
		{
			GL31.glUniformBlockBinding(glSmallComputeProgram, uniBlockSmall, 0);
			GL31.glUniformBlockBinding(glComputeProgram, uniBlockLarge, 0);

			GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, uniformBufferId);

			/*
			 * Compute is split into two separate programs 'small' and 'large' to
			 * save on GPU resources. Small will sort <= 512 faces, large will do <= 4096.
			 */

			// unordered
			GL20.glUseProgram(glUnorderedComputeProgram);

			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, modelBufferUnorderedId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, this.bufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, bufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, outBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, outUvBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, this.uvBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 6, uvBufferId);

			GL43.glDispatchCompute(unorderedModels, 1, 1);

			// small
			GL20.glUseProgram(glSmallComputeProgram);

			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, modelBufferSmallId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, this.bufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, bufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, outBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, outUvBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, this.uvBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 6, uvBufferId);

			GL43.glDispatchCompute(smallModels, 1, 1);

			// large
			GL20.glUseProgram(glComputeProgram);

			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, modelBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, this.bufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, bufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, outBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, outUvBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, this.uvBufferId);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 6, uvBufferId);

			GL43.glDispatchCompute(largeModels, 1, 1);

			GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

			if (textureArrayId == -1)
			{
				// lazy init textures as they may not be loaded at plugin start.
				// this will return -1 and retry if not all textures are loaded yet, too.
				textureArrayId = textureManager.initTextureArray(textureProvider, gl);
			}

			final Texture[] textures = textureProvider.getTextures();
			int renderHeightOff = client.getViewportYOffset();
			int renderWidthOff = client.getViewportXOffset();
			int renderCanvasHeight = canvasHeight;
			int renderViewportHeight = viewportHeight;
			int renderViewportWidth = viewportWidth;

			if (client.isStretchedEnabled())
			{
				Dimension dim = client.getStretchedDimensions();
				renderCanvasHeight = dim.height;

				double scaleFactorY = dim.getHeight() / canvasHeight;
				double scaleFactorX = dim.getWidth()  / canvasWidth;

				// Pad the viewport a little because having ints for our viewport dimensions can introduce off-by-one errors.
				final int padding = 1;

				// Ceil the sizes because even if the size is 599.1 we want to treat it as size 600 (i.e. render to the x=599 pixel).
				renderViewportHeight = (int) Math.ceil(scaleFactorY * (renderViewportHeight)) + padding * 2;
				renderViewportWidth  = (int) Math.ceil(scaleFactorX * (renderViewportWidth )) + padding * 2;

				// Floor the offsets because even if the offset is 4.9, we want to render to the x=4 pixel anyway.
				renderHeightOff      = (int) Math.floor(scaleFactorY * (renderHeightOff)) - padding;
				renderWidthOff       = (int) Math.floor(scaleFactorX * (renderWidthOff )) - padding;
			}

			glDpiAwareViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff, renderViewportWidth, renderViewportHeight);

			GL20.glUseProgram(glProgram);

			final int drawDistance = Math.max(0, Math.min(MAX_DISTANCE, config.drawDistance()));
			final int fogDepth = config.fogDepth();
			GL20.glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
			GL20.glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
			GL20.glUniform1i(uniFogDepth, fogDepth);
			GL20.glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);

			// Brightness happens to also be stored in the texture provider, so we use that
			GL20.glUniform1f(uniBrightness, (float) textureProvider.getBrightness());
			GL20.glUniform1f(uniSmoothBanding, config.smoothBanding() ? 0f : 1f);

			for (int id = 0; id < textures.length; ++id)
			{
				Texture texture = textures[id];
				if (texture == null)
				{
					continue;
				}

				textureProvider.load(id); // trips the texture load flag which lets textures animate

				textureOffsets[id * 2] = texture.getU();
				textureOffsets[id * 2 + 1] = texture.getV();
			}

			// Bind uniforms
			GL31.glUniformBlockBinding(glProgram, uniBlockMain, 0);
			GL20.glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1
			GL20.glUniform2fv(uniTextureOffsets, FloatBuffer.wrap(textureOffsets));

			// We just allow the GL to do face culling. Note this requires the priority renderer
			// to have logic to disregard culled faces in the priority depth testing.
			GL11.glEnable(GL11.GL_CULL_FACE);

			// Enable blending for alpha
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

			// Draw output of compute shaders
			GL30.glBindVertexArray(vaoHandle);

			GL20.glEnableVertexAttribArray(0);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outBufferId);
			GL30.glVertexAttribIPointer(0, 4, GL11.GL_INT, 0, 0);

			GL20.glEnableVertexAttribArray(1);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outUvBufferId);
			GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, 0, 0);

			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, targetBufferOffset);

			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_CULL_FACE);

			GL20.glUseProgram(0);
		}

		if (aaEnabled)
		{
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboSceneHandle);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
			GL30.glBlitFramebuffer(0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
				0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
				GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

			// Reset
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
		}

		vertexBuffer.clear();
		uvBuffer.clear();
		modelBuffer.clear();
		modelBufferSmall.clear();
		modelBufferUnordered.clear();

		targetBufferOffset = 0;
		smallModels = largeModels = unorderedModels = 0;
		tempOffset = 0;
		tempUvOffset = 0;

		glDeleteBuffer(gl, bufferId);
		glDeleteBuffer(gl, uvBufferId);
		glDeleteBuffer(gl, modelBufferId);
		glDeleteBuffer(gl, modelBufferSmallId);
		glDeleteBuffer(gl, modelBufferUnorderedId);
		glDeleteBuffer(gl, outBufferId);
		glDeleteBuffer(gl, outUvBufferId);

		// Texture on UI
		drawUi(canvasHeight, canvasWidth);

		glfwSwapBuffers(glfwWindow);

		drawManager.processDrawComplete(this::screenshot);
	}

	private void drawUi(final int canvasHeight, final int canvasWidth)
	{
		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		GL11.glEnable(GL11.GL_BLEND);

		vertexBuffer.clear(); // reuse vertex buffer for interface
		vertexBuffer.ensureCapacity(pixels.length);

		IntBuffer interfaceBuffer = vertexBuffer.getBuffer();
		interfaceBuffer.put(pixels);
		vertexBuffer.flip();

		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, interfaceTexture);

		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, interfaceBuffer);
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;
		}
		else
		{
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, interfaceBuffer);
		}

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
		}

		// Use the texture bound in the first pass
		GL20.glUseProgram(glUiProgram);
		GL20.glUniform1i(uniTex, 0);

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		if (client.isStretchedEnabled())
		{
			final int function = client.isStretchedFast() ? GL11.GL_NEAREST : GL11.GL_LINEAR;
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, function);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, function);
		}

		// Texture on UI
		GL30.glBindVertexArray(vaoUiHandle);
		GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

		// Reset
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		GL30.glBindVertexArray(0);
		GL20.glUseProgram(0);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glDisable(GL11.GL_BLEND);

		vertexBuffer.clear();
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width  = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width  = dim.width;
			height = dim.height;
		}

		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4)
			.order(ByteOrder.nativeOrder());

		GL11.glReadBuffer(GL11.GL_FRONT);
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				int r = buffer.get() & 0xff;
				int g = buffer.get() & 0xff;
				int b = buffer.get() & 0xff;
				buffer.get(); // alpha

				pixels[(height - y - 1) * width + x] = (r << 16) | (g << 8) | b;
			}
		}

		return image;
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		textureManager.animate(texture, diff);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		uploadScene();
	}

	private void uploadScene()
	{
		vertexBuffer.clear();
		uvBuffer.clear();

		sceneUploader.upload(client.getScene(), vertexBuffer, uvBuffer);

		vertexBuffer.flip();
		uvBuffer.flip();

		IntBuffer vertexBuffer = this.vertexBuffer.getBuffer();
		FloatBuffer uvBuffer = this.uvBuffer.getBuffer();

		if (bufferId != -1)
		{
			GLUtil.glDeleteBuffer(gl, bufferId);
			bufferId = -1;
		}

		if (uvBufferId != -1)
		{
			GLUtil.glDeleteBuffer(gl, uvBufferId);
			uvBufferId = -1;
		}

		bufferId = glGenBuffers(gl);
		uvBufferId = glGenBuffers(gl);

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer.limit() * Integer.BYTES, GL15.GL_STATIC_COPY);

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, uvBufferId);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, uvBuffer.limit() * Float.BYTES, GL15.GL_STATIC_COPY);

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

		vertexBuffer.clear();
		uvBuffer.clear();
	}

	/**
	 * Check is a model is visible and should be drawn.
	 */
	private boolean isVisible(Model model, int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int _x, int _y, int _z, long hash)
	{
		final int XYZMag = model.getXYZMag();
		final int zoom = client.get3dZoom();
		final int modelHeight = model.getModelHeight();

		int Rasterizer3D_clipMidX2 = client.getRasterizer3D_clipMidX2();
		int Rasterizer3D_clipNegativeMidX = client.getRasterizer3D_clipNegativeMidX();
		int Rasterizer3D_clipNegativeMidY = client.getRasterizer3D_clipNegativeMidY();
		int Rasterizer3D_clipMidY2 = client.getRasterizer3D_clipMidY2();

		int var11 = yawCos * _z - yawSin * _x >> 16;
		int var12 = pitchSin * _y + pitchCos * var11 >> 16;
		int var13 = pitchCos * XYZMag >> 16;
		int var14 = var12 + var13;
		if (var14 > 50)
		{
			int var15 = _z * yawSin + yawCos * _x >> 16;
			int var16 = (var15 - XYZMag) * zoom;
			if (var16 / var14 < Rasterizer3D_clipMidX2)
			{
				int var17 = (var15 + XYZMag) * zoom;
				if (var17 / var14 > Rasterizer3D_clipNegativeMidX)
				{
					int var18 = pitchCos * _y - var11 * pitchSin >> 16;
					int var19 = pitchSin * XYZMag >> 16;
					int var20 = (var18 + var19) * zoom;
					if (var20 / var14 > Rasterizer3D_clipNegativeMidY)
					{
						int var21 = (pitchCos * modelHeight >> 16) + var19;
						int var22 = (var18 - var21) * zoom;
						return var22 / var14 < Rasterizer3D_clipMidY2;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Draw a renderable in the scene
	 *
	 * @param renderable
	 * @param orientation
	 * @param pitchSin
	 * @param pitchCos
	 * @param yawSin
	 * @param yawCos
	 * @param x
	 * @param y
	 * @param z
	 * @param hash
	 */
	@Override
	public void draw(Renderable renderable, int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z, long hash)
	{
		// Model may be in the scene buffer
		if (renderable instanceof Model && ((Model) renderable).getSceneId() == sceneUploader.sceneId)
		{
			Model model = (Model) renderable;

			model.calculateBoundsCylinder();
			model.calculateExtreme(orientation);

			if (!isVisible(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash))
			{
				return;
			}

			client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

			int tc = Math.min(MAX_TRIANGLE, model.getTrianglesCount());
			int uvOffset = model.getUvBufferOffset();

			GpuIntBuffer b = bufferForTriangles(tc);

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(model.getBufferOffset());
			buffer.put(uvOffset);
			buffer.put(tc);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER | (model.getRadius() << 12) | orientation);
			buffer.put(x + client.getCameraX2()).put(y + client.getCameraY2()).put(z + client.getCameraZ2());

			targetBufferOffset += tc * 3;
		}
		else
		{
			// Temporary model (animated or otherwise not a static Model on the scene)
			Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
			if (model != null)
			{
				// Apply height to renderable from the model
				model.setModelHeight(model.getModelHeight());

				model.calculateBoundsCylinder();
				model.calculateExtreme(orientation);

				if (!isVisible(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash))
				{
					return;
				}

				client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

				boolean hasUv = model.getFaceTextures() != null;

				int faces = Math.min(MAX_TRIANGLE, model.getTrianglesCount());
				vertexBuffer.ensureCapacity(12 * faces);
				uvBuffer.ensureCapacity(12 * faces);
				int len = 0;
				for (int i = 0; i < faces; ++i)
				{
					len += sceneUploader.pushFace(model, i, vertexBuffer, uvBuffer);
				}

				GpuIntBuffer b = bufferForTriangles(faces);

				b.ensureCapacity(8);
				IntBuffer buffer = b.getBuffer();
				buffer.put(tempOffset);
				buffer.put(hasUv ? tempUvOffset : -1);
				buffer.put(len / 3);
				buffer.put(targetBufferOffset);
				buffer.put((model.getRadius() << 12) | orientation);
				buffer.put(x + client.getCameraX2()).put(y + client.getCameraY2()).put(z + client.getCameraZ2());

				tempOffset += len;
				if (hasUv)
				{
					tempUvOffset += len;
				}

				targetBufferOffset += len;
			}
		}
	}

	/**
	 * returns the correct buffer based on triangle count and updates model count
	 *
	 * @param triangles
	 * @return
	 */
	private GpuIntBuffer bufferForTriangles(int triangles)
	{
		if (triangles < SMALL_TRIANGLE_COUNT)
		{
			++smallModels;
			return modelBufferSmall;
		}
		else
		{
			++largeModels;
			return modelBuffer;
		}
	}

	private int getScaledValue(final double scale, final int value)
	{
		return SurfaceScaleUtils.scale(value, (float) scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		final AffineTransform t = ((Graphics2D) canvas.getGraphics()).getTransform();
		GL11.glViewport(
			getScaledValue(t.getScaleX(), x),
			getScaledValue(t.getScaleY(), y),
			getScaledValue(t.getScaleX(), width),
			getScaledValue(t.getScaleY(), height));
	}
}
