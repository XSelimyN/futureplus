#version 330

#define DESIGN 1

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:futureplus_sky_lib.glsl>

// Direct drawing (with the cache off). TextureMat[0] = (time, design, mode, family), TextureMat[1] = color
in vec3 vDir;
out vec4 fragColor;

void main() {
    fragColor = vec4(skyColor(normalize(vDir), TextureMat[0].x, TextureMat[0], TextureMat[1]), 1.0);
}
