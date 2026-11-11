#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Showing from the cache: a smooth fade between two finished equirect frames (Sampler0 = previous, Sampler1 = latest)
// (TextureMat[2].x = ratio). Manual bilinear, seamless horizontally.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec3 vDir;
out vec4 fragColor;

vec3 bilin(sampler2D s, vec2 pp, ivec2 sz) {
    ivec2 i0 = ivec2(floor(pp));
    vec2 f = pp - vec2(i0);
    int x0 = (i0.x % sz.x + sz.x) % sz.x, x1 = (x0 + 1) % sz.x;
    int y0 = clamp(i0.y, 0, sz.y - 1), y1 = clamp(i0.y + 1, 0, sz.y - 1);
    vec3 a = texelFetch(s, ivec2(x0, y0), 0).rgb, b = texelFetch(s, ivec2(x1, y0), 0).rgb;
    vec3 c = texelFetch(s, ivec2(x0, y1), 0).rgb, e = texelFetch(s, ivec2(x1, y1), 0).rgb;
    return mix(mix(a, b, f.x), mix(c, e, f.x), f.y);
}

void main() {
    vec3 d = normalize(vDir);
    float lon = atan(d.z, d.x);
    if (lon < 0.0) lon += 6.28318531;
    float lat = asin(clamp(d.y, -1.0, 1.0));
    vec2 uv = vec2(lon / 6.28318531, 0.5 - lat / 3.14159265);
    ivec2 sz = textureSize(Sampler1, 0);
    vec2 pp = uv * vec2(sz) - 0.5;
    float k = TextureMat[2].x;
    vec3 col = k >= 0.999 ? bilin(Sampler1, pp, sz) : mix(bilin(Sampler0, pp, sz), bilin(Sampler1, pp, sz), k);
    fragColor = vec4(col, 1.0);
}
