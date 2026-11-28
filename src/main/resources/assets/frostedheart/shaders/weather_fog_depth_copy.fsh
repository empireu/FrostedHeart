// FHShaders prepends the version line.
// Copies the main target's opaque depth texture into the translucent target's depth attachment via a full screen pass
// so translucent terrain tests against opaque depth without glBlitFramebuffer.
uniform sampler2D sceneDepth;

void main() {
    gl_FragDepth = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;
}
