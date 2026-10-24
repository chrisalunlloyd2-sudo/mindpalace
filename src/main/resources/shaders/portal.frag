#version 330 core
// MP-007 (#153): portal quad — unlit surface with a soft glowing rim band near
// the quad's edges. Alpha drives the door dissolve (0 = invisible, 1 = fully
// lit portal sheet). Unlit on purpose: a portal is a light source, so
// ambient/diffuse/specular would only mute it.

in vec3 WorldPos;
out vec4 FragColor;

uniform vec3 portalCenter;   // world-space quad centre
uniform vec3 portalHalf;     // HALF extents (x, y, z) of the drawn box
uniform vec3 portalColor;
uniform float alpha;         // global opacity (dissolve progress)
uniform float rimWidth;      // edge band as a fraction of half-extent (0 = off)
uniform int portalPlane;     // 0 = vertical door quad (in-plane X/Y, thin Z),
                             // 1 = flat quad (in-plane X/Z, thin Y),
                             // 2 = vertical doorway quad in the hallway wall
                             //     (in-plane Y/Z, thin X)

void main() {
    // Normalised in-plane distance from the centre: 0 at the centre, 1 at the
    // edge, on the two axes the quad actually spans. The thin (out-of-plane)
    // axis is ignored so the rim band follows all four edges on the visible
    // face instead of lighting up the whole thin side walls.
    vec3 rel = WorldPos - portalCenter;
    float d;
    if (portalPlane == 0) {
        d = max(abs(rel.x) / max(portalHalf.x, 1e-4),
                abs(rel.y) / max(portalHalf.y, 1e-4));
    } else if (portalPlane == 1) {
        d = max(abs(rel.x) / max(portalHalf.x, 1e-4),
                abs(rel.z) / max(portalHalf.z, 1e-4));
    } else {
        d = max(abs(rel.y) / max(portalHalf.y, 1e-4),
                abs(rel.z) / max(portalHalf.z, 1e-4));
    }
    float edge = clamp((d - (1.0 - rimWidth)) / max(rimWidth, 1e-4), 0.0, 1.0);
    float glow = mix(0.35, 1.0, smoothstep(0.0, 1.0, edge));
    FragColor = vec4(portalColor * glow, alpha);
}