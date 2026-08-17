package com.mindpalace.render;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OpenGL shader program wrapper.
 * Loads .vert and .frag from resources, compiles, links.
 */
public class Shader {
    private final int programId;

    public Shader(String vertPath, String fragPath) {
        int vertShader = compileShader(GL20.GL_VERTEX_SHADER, loadSourceStatic(vertPath));
        int fragShader = compileShader(GL20.GL_FRAGMENT_SHADER, loadSourceStatic(fragPath));

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vertShader);
        GL20.glAttachShader(programId, fragShader);
        GL20.glLinkProgram(programId);

        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId);
            throw new RuntimeException("Shader link failed: " + log);
        }

        GL20.glDeleteShader(vertShader);
        GL20.glDeleteShader(fragShader);
    }

    /** Construct from raw source strings (for inline shaders). */
    public Shader(String vertSrc, String fragSrc, boolean raw) {
        int vertShader = compileShader(GL20.GL_VERTEX_SHADER, vertSrc);
        int fragShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragSrc);

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vertShader);
        GL20.glAttachShader(programId, fragShader);
        GL20.glLinkProgram(programId);

        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId);
            throw new RuntimeException("Shader link failed: " + log);
        }

        GL20.glDeleteShader(vertShader);
        GL20.glDeleteShader(fragShader);
    }

    private static String loadSourceStatic(String path) {
        // Try classpath (works in JAR and filesystem)
        try (var in = Shader.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                return new String(in.readAllBytes());
            }
        } catch (Exception ignored) {}
        // Fallback: direct filesystem
        try {
            return Files.readString(Path.of("src/main/resources/" + path), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL20.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            throw new RuntimeException("Shader compile failed (" + type + "): " + log);
        }
        return shader;
    }

    public void bind() {
        GL20.glUseProgram(programId);
    }

    public void unbind() {
        GL20.glUseProgram(0);
    }

    public int getUniformLocation(String name) {
        return GL20.glGetUniformLocation(programId, name);
    }

    public void setUniform(String name, org.joml.Matrix4f mat) {
        int loc = getUniformLocation(name);
        float[] buf = new float[16];
        mat.get(buf);
        GL20.glUniformMatrix4fv(loc, false, buf);
    }

    public void setUniform(String name, org.joml.Vector3f vec) {
        int loc = getUniformLocation(name);
        GL20.glUniform3f(loc, vec.x, vec.y, vec.z);
    }

    public void setUniform(String name, org.joml.Vector4f vec) {
        int loc = getUniformLocation(name);
        GL20.glUniform4f(loc, vec.x, vec.y, vec.z, vec.w);
    }

    public void setUniform(String name, int i) {
        GL20.glUniform1i(getUniformLocation(name), i);
    }

    public void setUniform(String name, float f) {
        GL20.glUniform1f(getUniformLocation(name), f);
    }

    public void cleanup() {
        GL20.glDeleteProgram(programId);
    }

    public int getId() { return programId; }
}
