#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Futureplus skybox: a cube around the camera; the direction is passed to the fragment in world axes.
in vec3 Position;

out vec3 vDir;

void main() {
    vDir = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
