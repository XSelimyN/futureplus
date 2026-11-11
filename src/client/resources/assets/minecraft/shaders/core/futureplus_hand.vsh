#version 330

// Futureplus Hand Shader: full screen quad (corners already in clip space, -1..1).
in vec3 Position;

out vec2 uv;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    uv = (Position.xy + 1.0) * 0.5;
}
