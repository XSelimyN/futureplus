#version 330

#define DESIGN 3

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:futureplus_sky_lib.glsl>

// Cache baking: computes one row band of the equirectangular texture.
// Row 0 = top (+90°), last row = bottom (-90°); longitude 0..360° along x. TextureMat[2].zw = texture size.
out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / TextureMat[2].zw;
    float lon = uv.x * 6.28318531;
    float lat = 3.14159265 * (0.5 - uv.y);
    float cl = cos(lat);
    vec3 d = vec3(cl * cos(lon), sin(lat), cl * sin(lon));
    fragColor = vec4(skyColor(d, TextureMat[0].x, TextureMat[0], TextureMat[1]), 1.0);
}
