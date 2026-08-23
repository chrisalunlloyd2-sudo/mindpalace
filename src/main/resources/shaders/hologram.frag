#version 330 core
// Cortana hologram material — fresnel rim + scrolling data lines + scanlines.
// Emissive (no scene lights), opaque (no blending), zero lighting overhead.

in vec3 FragPos;
in vec3 Normal;
in vec2 TexCoord;

out vec4 FragColor;

uniform vec3 viewPos;
uniform vec3 tint;   // hologram base color (cyan/blue)
uniform float time;  // seconds, drives the animation

void main() {
    vec3 norm = normalize(Normal);
    vec3 viewDir = normalize(viewPos - FragPos);

    // Fresnel rim — edges glow brighter (the holographic silhouette)
    float fresnel = pow(1.0 - max(dot(norm, viewDir), 0.0), 2.5);

    // Scrolling circuit/data lines across the surface
    float dataX = 0.5 + 0.5 * sin(TexCoord.x * 48.0 + time * 3.0);
    float dataY = 0.5 + 0.5 * sin(TexCoord.y * 48.0 - time * 3.0);
    float data = dataX * dataY;

    // Horizontal scanlines in world space (subtle vertical banding)
    float scan = 0.82 + 0.18 * sin(FragPos.y * 28.0 - time * 1.5);

    // Core body + rim glow + data-line accent
    vec3 core  = tint * (0.30 + 0.20 * scan);
    vec3 rim   = tint * fresnel * 1.8;
    vec3 lines = vec3(0.75, 0.95, 1.0) * data * 0.30;

    vec3 color = core + rim + lines;

    // Digital shimmer flicker
    float flicker = 0.94 + 0.06 * sin(time * 9.0 + FragPos.y * 3.0);
    color *= flicker;

    FragColor = vec4(color, 1.0);
}
