#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Futureplus: full screen quad (corners already in clip space, -1..1).
in vec3 Position;

void main() {
    // The Projection block is bound in the pipeline; a NaN check that is never true keeps it active
    float keep = (ProjMat[0][0] != ProjMat[0][0]) ? 1.0 : 0.0;
    gl_Position = vec4(Position.x + keep, Position.y, 0.0, 1.0);
}
