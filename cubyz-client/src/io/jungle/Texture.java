package io.jungle;

import de.matthiasmann.twl.utils.PNGDecoder;
import de.matthiasmann.twl.utils.PNGDecoder.Format;
import io.cubyz.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL30.*;

public class Texture {

	protected int id = Integer.MIN_VALUE;
	protected int width, height;
	protected int pixelFormat;
	protected int internalFormat;
	protected InputStream is;

	public Texture(String fileName) throws IOException {
		this(new FileInputStream(fileName));
	}
	
	public Texture(File file) throws IOException {
		this(new FileInputStream(file));
	}

	public Texture(InputStream is) {
		this.is = is;
		create();
	}

	public Texture(int id) {
		this.id = id;
	}
	
	
	// depth texture
	public Texture(int width, int height, int pixelFormat) {
		this.id = glGenTextures();
		this.width = width;
		this.height = height;
		glBindTexture(GL_TEXTURE_2D, id);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, width, height, 0, pixelFormat, GL_FLOAT, (ByteBuffer) null);
	}
	
	public void create() {
		try {
			id = loadTexture(is);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void bind() {
		glBindTexture(GL_TEXTURE_2D, id);
	}
	
	public void unbind() {
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	public int getId() {
		return id;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	private int loadTexture(InputStream is) throws Exception {
		// Load Texture file
		PNGDecoder decoder = new PNGDecoder(is);

		// Load texture contents into a byte buffer
		width = decoder.getWidth();
		height = decoder.getHeight();
		ByteBuffer buf = ByteBuffer.allocateDirect(width * height << 2);
		decoder.decode(buf, width << 2, Format.RGBA);
		buf.flip();

		// Create a new OpenGL texture
		int textureId = glGenTextures();
		// Bind the texture
		glBindTexture(GL_TEXTURE_2D, textureId);

		// Tell OpenGL how to unpack the RGBA bytes. Each component is 1 byte size
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

		// Upload the texture data
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, decoder.getWidth(), decoder.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE,
				buf);

		if (Settings.mipmapping) {
			glGenerateMipmap(GL_TEXTURE_2D);
		}
		/* not used, and using it makes the game
		 look ugly. So disabled for now to not use useless memory */
		return textureId;
	}

	public void cleanup() {
		glDeleteTextures(id);
	}
}
