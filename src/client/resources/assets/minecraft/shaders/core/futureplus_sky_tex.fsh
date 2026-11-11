#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Futureplus skybox texture mode: equirectangular texture, manual bilinear sampling
// (smooth without relying on the sampler filter; seamless horizontal wrap).
uniform sampler2D Sampler0;

in vec3 vDir;
out vec4 fragColor;

vec3 fetch(ivec2 p, ivec2 sz) {
    p.x = (p.x % sz.x + sz.x) % sz.x;
    p.y = clamp(p.y, 0, sz.y - 1);
    return texelFetch(Sampler0, p, 0).rgb;
}

void main() {
    vec3 d = normalize(vDir);
    float lon = atan(d.z, d.x);
    if (lon < 0.0) lon += 6.28318531;
    float lat = asin(clamp(d.y, -1.0, 1.0));
    vec2 uv = vec2(lon / 6.28318531, 0.5 - lat / 3.14159265);
    ivec2 sz = textureSize(Sampler0, 0);
    vec2 pp = uv * vec2(sz) - 0.5;
    ivec2 i0 = ivec2(floor(pp));
    vec2 f = pp - vec2(i0);
    vec3 a = fetch(i0, sz), b = fetch(i0 + ivec2(1, 0), sz);
    vec3 c = fetch(i0 + ivec2(0, 1), sz), e = fetch(i0 + ivec2(1, 1), sz);
    fragColor = vec4(mix(mix(a, b, f.x), mix(c, e, f.x), f.y), 1.0);
}
