package com.lazydoglab.zisee.rtc.compute

import android.opengl.GLES11Ext
import android.opengl.GLES20
import org.webrtc.GlShader
import org.webrtc.GlUtil
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame

/** GL-owner-only, non-generative 16-tap downsample followed by causal, motion-gated denoise. */
internal class CameraQualityShader : AutoCloseable {
    private val vertices = GlUtil.createFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private var oes: GlShader? = null
    private var rgb: GlShader? = null
    private var temporal: GlShader? = null
    private var analysis: GlShader? = null

    /** Compile/link during call setup, before the first live camera frame has a deadline. */
    fun prepare() {
        oes = GlShader(VERTEX, resizeFragment(true))
        rgb = GlShader(VERTEX, resizeFragment(false))
        temporal = GlShader(VERTEX, TEMPORAL)
        analysis = GlShader(VERTEX, ANALYSIS)
    }

    private fun use(shader: GlShader, width: Int, height: Int) {
        shader.useProgram()
        shader.setVertexAttribArray("position", 2, vertices)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glColorMask(true, true, true, true)
    }

    fun resize(buffer: VideoFrame.TextureBuffer, width: Int, height: Int) {
        val external = buffer.type == VideoFrame.TextureBuffer.Type.OES
        val shader = if (external) oes ?: GlShader(VERTEX, resizeFragment(true)).also { oes = it }
            else rgb ?: GlShader(VERTEX, resizeFragment(false)).also { rgb = it }
        use(shader, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glBindTexture(target, buffer.textureId)
        GLES20.glUniform1i(shader.getUniformLocation("source"), 0)
        GLES20.glUniformMatrix4fv(shader.getUniformLocation("transform"), 1, false,
            RendererCommon.convertMatrixFromAndroidGraphicsMatrix(buffer.transformMatrix), 0)
        // cropAndScale only modifies the matrix; unscaled dimensions still describe real texels.
        val down = buffer.unscaledWidth > width || buffer.unscaledHeight > height
        GLES20.glUniform2f(shader.getUniformLocation("footprint"), if (down) 1f / width else 0f,
            if (down) 1f / height else 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(target, 0)
        GlUtil.checkNoGLES2Error("quality resize")
    }

    fun denoise(current: Int, previous: Int, older: Int, width: Int, height: Int, strength: Float) {
        val shader = temporal ?: GlShader(VERTEX, TEMPORAL).also { temporal = it }
        use(shader, width, height)
        listOf(current, previous, older).forEachIndexed { index, id ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glUniform1i(shader.getUniformLocation(listOf("source", "previous", "older")[index]), index)
        }
        GLES20.glUniform2f(shader.getUniformLocation("texel"), 1f / width, 1f / height)
        GLES20.glUniform1f(shader.getUniformLocation("strength"), strength)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        for (index in 2 downTo 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        GlUtil.checkNoGLES2Error("quality denoise")
    }

    fun analyze(current: Int, previous: Int, width: Int, height: Int) {
        val shader = analysis ?: GlShader(VERTEX, ANALYSIS).also { analysis = it }
        use(shader, 16, 9)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, current)
        GLES20.glUniform1i(shader.getUniformLocation("source"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previous)
        GLES20.glUniform1i(shader.getUniformLocation("previous"), 1)
        GLES20.glUniform2f(shader.getUniformLocation("texel"), 1f / width, 1f / height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun close() { oes?.release(); rgb?.release(); temporal?.release(); analysis?.release() }

    private companion object {
        const val VERTEX = """
            attribute vec2 position;
            varying vec2 uv;
            void main() { gl_Position = vec4(position, 0.0, 1.0); uv = (position + 1.0) * 0.5; }
        """
        fun resizeFragment(external: Boolean) = (if (external) "#extension GL_OES_EGL_image_external : require\n" else "") + """
            precision mediump float;
            varying vec2 uv;
            uniform ${if (external) "samplerExternalOES" else "sampler2D"} source;
            uniform mat4 transform;
            uniform vec2 footprint;
            void main() {
                if (footprint.x == 0.0 && footprint.y == 0.0) {
                    gl_FragColor = vec4(texture2D(source,(transform*vec4(uv,0.0,1.0)).xy).rgb,1.0);
                    return;
                }
                vec3 sum = vec3(0.0);
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        vec2 offset = (vec2(float(x), float(y)) - 1.5) * 0.25 * footprint;
                        vec2 coord = (transform * vec4(clamp(uv + offset, 0.0, 1.0), 0.0, 1.0)).xy;
                        sum += texture2D(source, coord).rgb;
                    }
                }
                gl_FragColor = vec4(sum / 16.0, 1.0);
            }
        """
        const val TEMPORAL = """
            precision mediump float;
            varying vec2 uv;
            uniform sampler2D source;
            uniform sampler2D previous;
            uniform sampler2D older;
            uniform vec2 texel;
            uniform float strength;
            float difference(vec3 a, vec3 b) { vec3 d = abs(a-b); return max(d.r, max(d.g, d.b)); }
            void main() {
                vec3 c = texture2D(source, uv).rgb;
                vec3 p = texture2D(previous, uv).rgb;
                vec3 o = texture2D(older, uv).rgb;
                float motion = max(difference(c,p), difference(c,o));
                float edge = max(difference(c, texture2D(source, uv + vec2(texel.x,0.0)).rgb),
                                 difference(c, texture2D(source, uv + vec2(0.0,texel.y)).rgb));
                float luma = dot(c, vec3(0.299, 0.587, 0.114));
                float weight = strength * (1.0-smoothstep(0.025,0.10,motion));
                weight *= (1.0-0.75*smoothstep(0.04,0.18,edge));
                weight *= mix(1.0,0.6,smoothstep(0.15,0.55,luma));
                // No future frames, recursive accumulation, sharpening, invented detail or gain.
                gl_FragColor = vec4(mix(c, (p*2.0+o)/3.0, weight), 1.0);
            }
        """
        const val ANALYSIS = """
            precision mediump float;
            varying vec2 uv;
            uniform sampler2D source;
            uniform sampler2D previous;
            uniform vec2 texel;
            float luma(vec3 c) { return dot(c, vec3(0.299,0.587,0.114)); }
            void main() {
                float c = luma(texture2D(source,uv).rgb);
                float p = luma(texture2D(previous,uv).rgb);
                float left = luma(texture2D(source,uv-vec2(texel.x,0.0)).rgb);
                float right = luma(texture2D(source,uv+vec2(texel.x,0.0)).rgb);
                float up = luma(texture2D(source,uv+vec2(0.0,texel.y)).rgb);
                float down = luma(texture2D(source,uv-vec2(0.0,texel.y)).rgb);
                float edge = max(abs(left-right),abs(up-down));
                float residual = abs(c-(left+right+up+down)*0.25)*(1.0-smoothstep(0.03,0.12,edge));
                gl_FragColor = vec4(c,abs(c-p),edge,residual);
            }
        """
    }
}
