#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Futureplus Shader module: crisp outline. Sampler0 = an unblurred copy of the vanilla outline target
// (a flat silhouette of each target in its own color, empty spots 0).
// Outside the silhouette, pixels at most R pixels (integer, circular) from the silhouette are painted with the NEAREST
// silhouette pixel's color, WITHOUT anti-aliasing -> a uniform, crisp line at any distance.
// TextureMat[0] = (width px, fill opacity, line opacity, -), TextureMat[1].xy = main target size
uniform sampler2D Sampler0;

out vec4 fragColor;

bool filled(vec4 c) { return c.a > 0.5 || dot(c.rgb, vec3(1.0)) > 0.02; }

void main() {
    ivec2 sz = textureSize(Sampler0, 0);
    vec2 mainSz = max(TextureMat[1].xy, vec2(1.0));
    // if the main target and the outline target differ in size (screen scaling), map the pixel center
    ivec2 ip = ivec2(floor(gl_FragCoord.xy * vec2(sz) / mainSz));
    int R = clamp(int(TextureMat[0].x + 0.5), 1, 4);
    float fillA = TextureMat[0].y;
    float lineA = TextureMat[0].z;

    vec4 c = texelFetch(Sampler0, clamp(ip, ivec2(0), sz - 1), 0);
    if (filled(c)) {
        if (fillA <= 0.003) discard;
        fragColor = vec4(c.rgb, fillA);
        return;
    }
    // R=1 -> 8 neighbours (no gaps at corners), larger R -> a round disk
    float lim = (float(R) + 0.5) * (float(R) + 0.5);
    float best = 1e9;
    vec4 col = vec4(0.0);
    for (int dy = -R; dy <= R; dy++) {
        for (int dx = -R; dx <= R; dx++) {
            float d = float(dx * dx + dy * dy);
            if (d > lim || d >= best) continue;
            ivec2 q = ip + ivec2(dx, dy);
            if (q.x < 0 || q.y < 0 || q.x >= sz.x || q.y >= sz.y) continue;
            vec4 s = texelFetch(Sampler0, q, 0);
            if (filled(s)) { best = d; col = s; }
        }
    }
    if (best > 1e8) discard;
    fragColor = vec4(col.rgb, lineA);
}
