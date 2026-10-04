package com.aura.avatarstudio.renderer

/** Skybox program: renders the prefiltered environment cube. */
object SkyboxShaders {

    const val VERTEX = """#version 300 es
precision highp float;

uniform mat4 uViewRot;
uniform mat4 uProj;

in vec3 aPosition;

out vec3 vDir;

void main() {
    vDir = aPosition;
    vec4 p = uProj * uViewRot * vec4(aPosition, 1.0);
    gl_Position = p.xyww;
}
"""

    const val FRAGMENT = """#version 300 es
precision highp float;

uniform samplerCube uSkybox;
uniform float uExposure;

in vec3 vDir;

out vec4 outColor;

void main() {
    vec3 color = texture(uSkybox, normalize(vDir)).rgb;
    color *= uExposure;
    color = color / (color + vec3(1.0));
    color = pow(color, vec3(1.0 / 2.2));
    outColor = vec4(color, 1.0);
}
"""
}

/** Demo-only helpers: ground grid + point light billboards. */
object DemoShaders {

    const val GRID_VERTEX = """#version 300 es
precision highp float;

uniform mat4 uView;
uniform mat4 uProj;
uniform float uGridSize;
uniform float uFadeStart;

in vec3 aPosition;

out float vFade;

void main() {
    gl_Position = uProj * uView * vec4(aPosition, 1.0);
    float dist = length(aPosition.xz);
    vFade = clamp(1.0 - max(dist - uFadeStart, 0.0) / uFadeStart, 0.0, 1.0);
}
"""

    const val GRID_FRAGMENT = """#version 300 es
precision highp float;

in float vFade;

out vec4 outColor;

void main() {
    outColor = vec4(0.35, 0.38, 0.45, 0.28 * vFade);
}
"""

    const val LIGHT_VERTEX = """#version 300 es
precision highp float;

uniform mat4 uView;
uniform mat4 uProj;
uniform mat4 uModel;

in vec3 aPosition;

void main() {
    gl_Position = uProj * uView * uModel * vec4(aPosition, 1.0);
}
"""

    const val LIGHT_FRAGMENT = """#version 300 es
precision highp float;

uniform vec3 uColor;

out vec4 outColor;

void main() {
    outColor = vec4(uColor, 1.0);
}
"""
}
