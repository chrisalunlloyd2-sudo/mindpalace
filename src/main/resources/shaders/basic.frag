#version 330 core

in vec3 FragPos;
in vec3 Normal;
in vec2 TexCoord;

out vec4 FragColor;

uniform vec3 lightPos;
uniform vec3 lightColor;
uniform vec3 viewPos;
uniform float ambientStrength;
uniform sampler2D textureSampler;
uniform int useTexture;
uniform vec3 tintColor;
uniform float fogEnabled;

void main() {
    // Ambient
    vec3 ambient = ambientStrength * lightColor;

    // Diffuse
    vec3 norm = normalize(Normal);
    vec3 lightDir = normalize(lightPos - FragPos);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * lightColor;

    // Specular
    float specularStrength = 0.3;
    vec3 viewDir = normalize(viewPos - FragPos);
    vec3 reflectDir = reflect(-lightDir, norm);
    float spec = pow(max(dot(viewDir, reflectDir), 0.0), 32);
    vec3 specular = specularStrength * spec * lightColor;

    // Base color from texture or default
    vec3 baseColor;
    if (useTexture == 1) {
        baseColor = texture(textureSampler, TexCoord).rgb;
    } else {
        baseColor = vec3(0.5, 0.4, 0.3); // default brown
    }
    baseColor *= tintColor; // per-room language accent

    vec3 result = (ambient + diffuse + specular) * baseColor;

    // Distance fog — soft atmospheric depth. Tuned for the 300×250m open
    // world: starts at 60m and fully fades by ~260m, so the horizon recedes
    // instead of dissolving into a violet void 20m out (the old "lost in
    // space" bug). Fog color is a pale sky haze, not deep violet.
    float dist = length(viewPos - FragPos);
    float fogFactor = clamp((dist - 60.0) / 200.0, 0.0, 0.85);
    vec3 fogColor = vec3(0.55, 0.65, 0.78); // pale sky haze
    result = mix(result, fogColor, fogFactor * fogEnabled);

    FragColor = vec4(result, 1.0);
}
