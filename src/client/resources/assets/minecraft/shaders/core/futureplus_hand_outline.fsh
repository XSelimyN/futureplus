#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Futureplus Shader module: an outline around the first person hand (and the item in it).
// Sampler0 = main depth texture; it is cleared to 1.0 before the hand is drawn, so pixels with depth < 1 = the hand.
// TextureMat[0] = (line width px, fill opacity, softness, -), TextureMat[1] = color (rgb) + opacity
uniform sampler2D Sampler0;

out vec4 fragColor;

const float TAU = 6.28318531;

float handAt(ivec2 p, ivec2 sz) {
    p = clamp(p, ivec2(0), sz - 1);
    return texelFetch(Sampler0, p, 0).r < 0.99999 ? 1.0 : 0.0;
}

void main() {
    ivec2 sz = textureSize(Sampler0, 0);
    ivec2 ip = ivec2(gl_FragCoord.xy);
    float width = max(1.0, TextureMat[0].x);
    float fillA = TextureMat[0].y;
    vec4 col = TextureMat[1];

    float inside = handAt(ip, sz);
    if (inside > 0.5) {
        if (fillA <= 0.003) discard;
        fragColor = vec4(col.rgb, fillA);
        return;
    }
    // outside pixel: is there hand within the 'width' radius? (two rings x 16 directions; an exact edge for thin lines)
    float hit = 0.0;
    for (int ring = 1; ring <= 2; ring++) {
        float r = width * float(ring) / 2.0;
        for (int k = 0; k < 16; k++) {
            float a = (float(k) + 0.5 * float(ring)) / 16.0 * TAU;
            hit = max(hit, handAt(ip + ivec2(round(vec2(cos(a), sin(a)) * r)), sz));
        }
    }
    // the nearest 4 neighbours: no gaps even in a 1 px line
    hit = max(hit, max(max(handAt(ip + ivec2(1, 0), sz), handAt(ip - ivec2(1, 0), sz)),
                       max(handAt(ip + ivec2(0, 1), sz), handAt(ip - ivec2(0, 1), sz))));
    if (hit < 0.5) discard;
    fragColor = vec4(col.rgb, col.a);
}
