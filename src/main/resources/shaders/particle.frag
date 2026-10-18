#version 330 core

in vec2 vUV;
in vec4 vColor;

out vec4 FragColor;

// MP-028: soft round dot computed from the quad's UV, so no texture is needed.
// Additive blending (set by ParticleBatch) turns the falloff into a glow.
void main() {
    vec2 d = vUV * 2.0 - 1.0;
    float r2 = dot(d, d);
    if (r2 > 1.0) discard;
    float a = 1.0 - r2;
    a *= a;
    FragColor = vec4(vColor.rgb, vColor.a * a);
}
