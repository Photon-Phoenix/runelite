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

import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.util.Scanner;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

@Slf4j
class GLUtil
{
	private static final int ERR_LEN = 1024;

	private static final int[] buf = new int[1];

	public static int glGetInteger(GL43 gl, int pname)
	{
		GL11.glGetIntegerv(pname, IntBuffer.wrap(buf));
		return buf[0];
	}

	static int glGetShader(GL43 gl, int shader, int pname)
	{
		GL20.glGetShaderiv(shader, pname, IntBuffer.wrap(buf));
		assert buf[0] > -1;
		return buf[0];
	}

	static int glGetProgram(GL43 gl, int program, int pname)
	{
		GL20.glGetProgramiv(program, pname, IntBuffer.wrap(buf));
		assert buf[0] > -1;
		return buf[0];
	}

	static String glGetShaderInfoLog(GL43 gl, int shader)
	{
		byte[] err = new byte[ERR_LEN];
		GL20.glGetShaderInfoLog(shader, IntBuffer.wrap(buf, 0, ERR_LEN), ByteBuffer.wrap(err));
		return new String(err);
	}

	static String glGetProgramInfoLog(GL43 gl, int program)
	{
		byte[] err = new byte[ERR_LEN];
		GL20.glGetProgramInfoLog(program, IntBuffer.wrap(buf, 0, ERR_LEN), ByteBuffer.wrap(err));
		return new String(err);
	}

	static int glGenVertexArrays(GL43 gl)
	{
		GL30.glGenVertexArrays(IntBuffer.wrap(buf));
		return buf[0];
	}

	static void glDeleteVertexArrays(GL43 gl, int vertexArray)
	{
		buf[0] = vertexArray;
		GL30.glDeleteVertexArrays(IntBuffer.wrap(buf));
	}

	static int glGenBuffers(GL43 gl)
	{
		GL15.glGenBuffers(IntBuffer.wrap(buf));
		return buf[0];
	}

	static void glDeleteBuffer(GL43 gl, int buffer)
	{
		buf[0] = buffer;
		GL15.glDeleteBuffers(IntBuffer.wrap(buf));
	}

	static int glGenTexture(GL43 gl)
	{
		GL11.glGenTextures(IntBuffer.wrap(buf));
		return buf[0];
	}

	static void glDeleteTexture(GL43 gl, int texture)
	{
		buf[0] = texture;
		GL11.glDeleteTextures(IntBuffer.wrap(buf));
	}

	static int glGenFrameBuffer(GL43 gl)
	{
		GL30.glGenFramebuffers(IntBuffer.wrap(buf));
		return buf[0];
	}

	static void glDeleteFrameBuffer(GL43 gl, int frameBuffer)
	{
		buf[0] = frameBuffer;
		GL30.glDeleteFramebuffers(IntBuffer.wrap(buf));
	}

	static int glGenRenderbuffer(GL43 gl)
	{
		GL30.glGenRenderbuffers(IntBuffer.wrap(buf));
		return buf[0];
	}

	static void glDeleteRenderbuffers(GL43 gl, int renderBuffer)
	{
		buf[0] = renderBuffer;
		GL30.glDeleteRenderbuffers(IntBuffer.wrap(buf));
	}

	static void loadShaders(GL43 gl, int glProgram, int glVertexShader, int glGeometryShader, int glFragmentShader,
							String vertexShaderStr, String geomShaderStr, String fragShaderStr) throws ShaderException
	{
		compileAndAttach(gl, glProgram, glVertexShader, vertexShaderStr);

		if (glGeometryShader != -1)
		{
			compileAndAttach(gl, glProgram, glGeometryShader, geomShaderStr);
		}

		compileAndAttach(gl, glProgram, glFragmentShader, fragShaderStr);

		GL20.glLinkProgram(glProgram);

		if (glGetProgram(gl, glProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
		{
			String err = glGetProgramInfoLog(gl, glProgram);
			throw new ShaderException(err);
		}

		GL20.glValidateProgram(glProgram);

		if (glGetProgram(gl, glProgram, GL20.GL_VALIDATE_STATUS) == GL11.GL_FALSE)
		{
			String err = glGetProgramInfoLog(gl, glProgram);
			throw new ShaderException(err);
		}
	}

	static void loadComputeShader(GL43 gl, int glProgram, int glComputeShader, String str) throws ShaderException
	{
		compileAndAttach(gl, glProgram, glComputeShader, str);

		GL20.glLinkProgram(glProgram);

		if (glGetProgram(gl, glProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
		{
			String err = glGetProgramInfoLog(gl, glProgram);
			throw new ShaderException(err);
		}

		GL20.glValidateProgram(glProgram);

		if (glGetProgram(gl, glProgram, GL20.GL_VALIDATE_STATUS) == GL11.GL_FALSE)
		{
			String err = glGetProgramInfoLog(gl, glProgram);
			throw new ShaderException(err);
		}
	}

	private static void compileAndAttach(GL43 gl, int program, int shader, String source) throws ShaderException
	{
		GL20.glShaderSource(shader, new String[]{source});
		GL20.glCompileShader(shader);

		if (glGetShader(gl, shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE)
		{
			GL20.glAttachShader(program, shader);
		}
		else
		{
			String err = glGetShaderInfoLog(gl, shader);
			throw new ShaderException(err);
		}
	}

	static String inputStreamToString(InputStream in)
	{
		Scanner scanner = new Scanner(in).useDelimiter("\\A");
		return scanner.next();
	}
}
