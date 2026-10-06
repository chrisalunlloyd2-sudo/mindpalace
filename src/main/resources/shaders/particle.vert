#version 330 core

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aUV;
layout (location = 2) in vec4 aColor;

out vec2 vUV;
out vec4 vColor;

uniform mat4 projection;
uniform mat4 view;

void main() {
    vUV = aUV;
    vColor = aColor;
    gl_Position = projection * view * vec4(aPos, 1.0);
}
