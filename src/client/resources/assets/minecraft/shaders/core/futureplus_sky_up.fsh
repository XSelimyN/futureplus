#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Futureplus: upscales the sky drawn at low resolution to the screen (manual bilinear, independent of the sampler setting).
// TextureMat[2] = (scaleX, scaleY, small width, small height)
uniform sampler2D Sampler0;

in vec3 vDir;
out vec4 fragColor;

void main() {
    vec4 q = TextureMat[2];
    ivec2 mx = ivec2(q.zw) - 1;
    vec2 pos = gl_FragCoord.xy * q.xy - 0.5;
    vec2 fl = floor(pos);
    vec2 f = pos - fl;
    ivec2 i0 = ivec2(fl);
    vec3 a = texelFetch(Sampler0, clamp(i0, ivec2(0), mx), 0).rgb;
    vec3 b = texelFetch(Sampler0, clamp(i0 + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c = texelFetch(Sampler0, clamp(i0 + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 d = texelFetch(Sampler0, clamp(i0 + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    fragColor = vec4(mix(mix(a, b, f.x), mix(c, d, f.x), f.y), 1.0);
}
