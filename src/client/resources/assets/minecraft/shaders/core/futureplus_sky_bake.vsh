#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Cache baking: squeezes a screen sized quad into the wanted row band. TextureMat[3].xy = band (clip space y0, y1)
in vec3 Position;

void main() {
    vec4 b = TextureMat[3];
    // ProjMat isn't needed here, but the pipeline binds the Projection block; to keep the block "active"
    // we touch it with a NaN check that is never true (the compiler can't drop it, the result doesn't change).
    float keep = (ProjMat[0][0] != ProjMat[0][0]) ? 1.0 : 0.0;
    gl_Position = vec4(Position.x + keep, mix(b.x, b.y, Position.y * 0.5 + 0.5), 0.0, 1.0);
}
