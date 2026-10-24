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
// MP-008 (#154): baked interior preview (0 = off — plain MP-007 rim sheet)
uniform sampler2D previewTex;   // baked interior (256² per revealed room)
uniform float previewOn;        // 0/1 — set with the room's preview texture id
uniform float previewParallax;  // view-angle offset strength (sheet "pane" depth)
uniform float previewShimmer;   // slow ambient shimmer strength
uniform float time;             // seconds — drives the shimmer
uniform vec3 viewPos;           // camera position (parallax)

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

    // MP-008 (#154): sampled interior window. Plane-2 span is Y/Z, so the bake
    // remap is u = 0.5 - rel.z/2half.z (door-leaf +u faces the hallway side
    // the camera approaches from; bake yaw runs −X/+X by side) and
    // v = 0.5 + rel.y/2half.y. Parallax: viewer lateral offset along the wall
    // (viewPos − center, Z axis) shifts the sample like a pane set behind the
    // wall. Shimmer: a gentle sinusoid, brightest at the pane's centre.
    if (previewOn > 0.5) {
        vec2 uv = vec2(0.5 - rel.z / max(portalHalf.z, 1e-4) * 0.5,
                       0.5 + rel.y / max(portalHalf.y, 1e-4) * 0.5);
        vec3 paneDist = viewPos - portalCenter;
        float latShift = clamp(paneDist.z * previewParallax, -0.18, 0.18);
        uv.x = clamp(uv.x + latShift, 0.001, 0.999);
        uv.y = clamp(uv.y, 0.001, 0.999);
        vec3 pane = texture(previewTex, uv).rgb;
        float shimmerAmt = previewShimmer
            * (0.55 + 0.45 * sin(time * 1.7 + uv.x * 5.0))
            * (1.0 - smoothstep(0.55, 1.0, d));  // rim stays pure
        pane *= 1.0 + shimmerAmt;
        vec3 rimCol = portalColor * glow;
        FragColor = vec4(mix(rimCol, pane, 1.0 - smoothstep(0.82, 0.98, d)),
                         alpha);
        return;
    }

    FragColor = vec4(portalColor * glow, alpha);
}