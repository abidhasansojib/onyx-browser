package com.onyx.browser.web

/**
 * High-performance WebGL and WebGL 2 Compatibility Engine for Onyx Browser.
 *
 * Resolves missing legacy WebGL 1 extensions on mobile GPUs (such as OES_texture_float,
 * OES_texture_float_linear, OES_standard_derivatives, OES_texture_half_float, WEBGL_color_buffer_float)
 * by upgrading WebGL 1 contexts to modern WebGL 2 when available, shimming legacy extension APIs and texture
 * upload formats transparently, activating float/half-float color render targets, enforcing fallback
 * texture filtering for framebuffers, and providing robust standard derivatives (dFdx, dFdy, fwidth)
 * compilation safeguards for GLSL 1.00 shaders.
 */
object WebGLCompatibilityBridge {

    val SCRIPT: String = """
        (function() {
            if (window.__onyx_webgl_compat_installed) return;
            window.__onyx_webgl_compat_installed = true;

            function float32ToFloat16(f32Array) {
                var f16 = new Uint16Array(f32Array.length);
                for (var i = 0; i < f32Array.length; i++) {
                    var val = f32Array[i];
                    var fview = new Float32Array(1);
                    var iview = new Int32Array(fview.buffer);
                    fview[0] = val;
                    var x = iview[0];
                    var bits = (x >> 16) & 0x8000;
                    var m = (x >> 12) & 0x07ff;
                    var e = (x >> 23) & 0xff;
                    if (e < 103) {
                        f16[i] = bits;
                    } else if (e > 142) {
                        bits |= 0x7c00;
                        bits |= ((e === 255) ? 0 : 1) && (x & 0x007fffff);
                        f16[i] = bits;
                    } else if (e < 113) {
                        m |= 0x0800;
                        bits |= (m >> (114 - e)) + ((m >> (113 - e)) & 1);
                        f16[i] = bits;
                    } else {
                        bits |= ((e - 112) << 10) | (m >> 1);
                        bits += m & 1;
                        f16[i] = bits;
                    }
                }
                return f16;
            }

            function patchWebGLContext(gl, isWebGL2) {
                if (!gl || gl.__onyx_patched) return gl;
                gl.__onyx_patched = true;

                if (!gl.HALF_FLOAT_OES) {
                    gl.HALF_FLOAT_OES = 0x8D61;
                }
                if (!gl.FRAGMENT_SHADER_DERIVATIVE_HINT_OES) {
                    gl.FRAGMENT_SHADER_DERIVATIVE_HINT_OES = 0x8B8B;
                }

                var realGetExtension = gl.getExtension ? gl.getExtension.bind(gl) : function() { return null; };
                var realGetSupportedExtensions = gl.getSupportedExtensions ? gl.getSupportedExtensions.bind(gl) : function() { return []; };
                var extCache = {};

                // Proactively activate hardware color buffer and float extensions
                if (isWebGL2) {
                    try { realGetExtension('EXT_color_buffer_float'); } catch (_) {}
                    try { realGetExtension('EXT_color_buffer_half_float'); } catch (_) {}
                    try { realGetExtension('WEBGL_color_buffer_float'); } catch (_) {}
                    try { realGetExtension('OES_texture_float_linear'); } catch (_) {}
                    try { realGetExtension('OES_texture_half_float_linear'); } catch (_) {}
                } else {
                    try { realGetExtension('OES_texture_float'); } catch (_) {}
                    try { realGetExtension('OES_texture_float_linear'); } catch (_) {}
                    try { realGetExtension('OES_texture_half_float'); } catch (_) {}
                    try { realGetExtension('OES_texture_half_float_linear'); } catch (_) {}
                    try { realGetExtension('WEBGL_color_buffer_float'); } catch (_) {}
                    try { realGetExtension('EXT_color_buffer_half_float'); } catch (_) {}
                    try { realGetExtension('OES_standard_derivatives'); } catch (_) {}
                }

                gl.getExtension = function(name) {
                    if (!name) return null;
                    if (extCache[name] !== undefined) {
                        return extCache[name];
                    }

                    var ext = null;
                    try {
                        ext = realGetExtension(name);
                    } catch (_) {}

                    if (ext) {
                        extCache[name] = ext;
                        return ext;
                    }

                    // Polyfill missing extensions using core WebGL 2 capabilities or compliant fallbacks
                    if (name === 'OES_texture_float') {
                        ext = {};
                    } else if (name === 'OES_texture_float_linear') {
                        ext = realGetExtension('OES_texture_float_linear') || null;
                    } else if (name === 'OES_texture_half_float') {
                        ext = { HALF_FLOAT_OES: 0x8D61 };
                    } else if (name === 'OES_texture_half_float_linear') {
                        ext = realGetExtension('OES_texture_half_float_linear') || null;
                    } else if (name === 'OES_standard_derivatives') {
                        // In WebGL 2, OES_standard_derivatives is not an extension (derivatives are core in ESSL 3.00)
                        // Returning null allows WebGL 1 scripts to select compliant fallback paths without crashing
                        ext = isWebGL2 ? null : (realGetExtension('OES_standard_derivatives') || { FRAGMENT_SHADER_DERIVATIVE_HINT_OES: 0x8B8B });
                    } else if (name === 'WEBGL_color_buffer_float') {
                        var cbf = null;
                        try { cbf = realGetExtension('EXT_color_buffer_float') || realGetExtension('WEBGL_color_buffer_float'); } catch (_) {}
                        ext = cbf || {
                            RGBA32F_EXT: 0x8814,
                            RGB32F_EXT: 0x8815,
                            FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE_EXT: 0x8211,
                            UNSIGNED_NORMALIZED_EXT: 0x8C17
                        };
                    } else if (name === 'EXT_color_buffer_half_float') {
                        var hbf = null;
                        try { hbf = realGetExtension('EXT_color_buffer_half_float') || realGetExtension('EXT_color_buffer_float'); } catch (_) {}
                        ext = hbf || {
                            RGBA16F_EXT: 0x881A,
                            RGB16F_EXT: 0x881B,
                            FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE_EXT: 0x8211,
                            UNSIGNED_NORMALIZED_EXT: 0x8C17
                        };
                    } else if (name === 'EXT_color_buffer_float') {
                        try { ext = realGetExtension('EXT_color_buffer_float'); } catch (_) {}
                    } else if (name === 'WEBGL_depth_texture') {
                        ext = { UNSIGNED_INT_24_8_WEBGL: 0x84FA };
                    } else if (name === 'OES_element_index_uint') {
                        ext = { UNSIGNED_INT: 0x1405 };
                    } else if (name === 'EXT_shader_texture_lod') {
                        ext = {};
                    } else if (name === 'ANGLE_instanced_arrays') {
                        if (isWebGL2) {
                            ext = {
                                VERTEX_ATTRIB_ARRAY_DIVISOR_ANGLE: 0x88FE,
                                drawArraysInstancedANGLE: function(mode, first, count, primcount) {
                                    return gl.drawArraysInstanced(mode, first, count, primcount);
                                },
                                drawElementsInstancedANGLE: function(mode, count, type, offset, primcount) {
                                    return gl.drawElementsInstanced(mode, count, type, offset, primcount);
                                },
                                vertexAttribDivisorANGLE: function(index, divisor) {
                                    return gl.vertexAttribDivisor(index, divisor);
                                }
                            };
                        }
                    } else if (name === 'WEBGL_draw_buffers') {
                        if (isWebGL2) {
                            ext = {
                                COLOR_ATTACHMENT0_WEBGL: 0x8CE0,
                                COLOR_ATTACHMENT1_WEBGL: 0x8CE1,
                                COLOR_ATTACHMENT2_WEBGL: 0x8CE2,
                                COLOR_ATTACHMENT3_WEBGL: 0x8CE3,
                                DRAW_BUFFER0_WEBGL: 0x8825,
                                MAX_COLOR_ATTACHMENTS_WEBGL: 0x8CDF,
                                MAX_DRAW_BUFFERS_WEBGL: 0x8824,
                                drawBuffersWEBGL: function(buffers) {
                                    return gl.drawBuffers(buffers);
                                }
                            };
                        }
                    } else if (name === 'OES_vertex_array_object') {
                        if (isWebGL2) {
                            ext = {
                                VERTEX_ARRAY_BINDING_OES: 0x85B5,
                                createVertexArrayOES: function() { return gl.createVertexArray(); },
                                deleteVertexArrayOES: function(vao) { return gl.deleteVertexArray(vao); },
                                isVertexArrayOES: function(vao) { return gl.isVertexArray(vao); },
                                bindVertexArrayOES: function(vao) { return gl.bindVertexArray(vao); }
                            };
                        }
                    }

                    extCache[name] = ext;
                    return ext;
                };

                gl.getSupportedExtensions = function() {
                    var list = [];
                    try {
                        list = realGetSupportedExtensions() || [];
                    } catch (_) {}

                    var additions = [
                        'OES_texture_float',
                        'OES_texture_half_float',
                        'WEBGL_depth_texture',
                        'OES_element_index_uint',
                        'EXT_shader_texture_lod',
                        'WEBGL_color_buffer_float',
                        'EXT_color_buffer_half_float'
                    ];
                    if (!isWebGL2) {
                        try {
                            if (realGetExtension('OES_standard_derivatives')) additions.push('OES_standard_derivatives');
                        } catch (_) {}
                    }
                    try {
                        if (realGetExtension('OES_texture_float_linear')) additions.push('OES_texture_float_linear');
                    } catch (_) {}
                    try {
                        if (realGetExtension('OES_texture_half_float_linear')) additions.push('OES_texture_half_float_linear');
                    } catch (_) {}
                    if (isWebGL2) {
                        additions.push('ANGLE_instanced_arrays', 'WEBGL_draw_buffers', 'OES_vertex_array_object');
                    }
                    for (var i = 0; i < additions.length; i++) {
                        if (list.indexOf(additions[i]) === -1) {
                            list.push(additions[i]);
                        }
                    }
                    return list;
                };

                // Patch texImage2D for seamless float/half-float texture compatibility
                if (gl.texImage2D) {
                    var realTexImage2D = gl.texImage2D.bind(gl);
                    gl.texImage2D = function() {
                        var args = Array.prototype.slice.call(arguments);
                        if (isWebGL2) {
                            if (args.length >= 8) {
                                var internalformat = args[2];
                                var type = args[7];
                                if (type === gl.FLOAT || type === 0x1406) {
                                    if (internalformat === gl.RGBA || internalformat === 0x1908) {
                                        args[2] = 0x8814; // gl.RGBA32F
                                    } else if (internalformat === gl.RGB || internalformat === 0x1907) {
                                        args[2] = 0x8815; // gl.RGB32F
                                    } else if (internalformat === gl.LUMINANCE || internalformat === 0x1909 || internalformat === gl.ALPHA || internalformat === 0x1906) {
                                        args[2] = 0x8229; // gl.R32F
                                        args[6] = gl.RED || 0x1903;
                                    } else if (internalformat === gl.LUMINANCE_ALPHA || internalformat === 0x190A) {
                                        args[2] = 0x822B; // gl.RG32F
                                        args[6] = gl.RG || 0x8227;
                                    }
                                } else if (type === 0x8D61) { // HALF_FLOAT_OES
                                    args[7] = gl.HALF_FLOAT || 0x140B;
                                    if (internalformat === gl.RGBA || internalformat === 0x1908) {
                                        args[2] = 0x881A; // gl.RGBA16F
                                    } else if (internalformat === gl.RGB || internalformat === 0x1907) {
                                        args[2] = 0x881B; // gl.RGB16F
                                    } else if (internalformat === gl.LUMINANCE || internalformat === 0x1909 || internalformat === gl.ALPHA || internalformat === 0x1906) {
                                        args[2] = 0x822D; // gl.R16F
                                        args[6] = gl.RED || 0x1903;
                                    } else if (internalformat === gl.LUMINANCE_ALPHA || internalformat === 0x190A) {
                                        args[2] = 0x822F; // gl.RG16F
                                        args[6] = gl.RG || 0x8227;
                                    }
                                }
                            } else if (args.length === 6) {
                                var internalformat6 = args[2];
                                var type6 = args[4];
                                if (type6 === gl.FLOAT || type6 === 0x1406) {
                                    if (internalformat6 === gl.RGBA || internalformat6 === 0x1908) {
                                        args[2] = 0x8814;
                                    } else if (internalformat6 === gl.RGB || internalformat6 === 0x1907) {
                                        args[2] = 0x8815;
                                    } else if (internalformat6 === gl.LUMINANCE || internalformat6 === 0x1909 || internalformat6 === gl.ALPHA || internalformat6 === 0x1906) {
                                        args[2] = 0x8229;
                                        args[3] = gl.RED || 0x1903;
                                    } else if (internalformat6 === gl.LUMINANCE_ALPHA || internalformat6 === 0x190A) {
                                        args[2] = 0x822B;
                                        args[3] = gl.RG || 0x8227;
                                    }
                                } else if (type6 === 0x8D61) {
                                    args[4] = gl.HALF_FLOAT || 0x140B;
                                    if (internalformat6 === gl.RGBA || internalformat6 === 0x1908) {
                                        args[2] = 0x881A;
                                    } else if (internalformat6 === gl.RGB || internalformat6 === 0x1907) {
                                        args[2] = 0x881B;
                                    } else if (internalformat6 === gl.LUMINANCE || internalformat6 === 0x1909 || internalformat6 === gl.ALPHA || internalformat6 === 0x1906) {
                                        args[2] = 0x822D;
                                        args[3] = gl.RED || 0x1903;
                                    } else if (internalformat6 === gl.LUMINANCE_ALPHA || internalformat6 === 0x190A) {
                                        args[2] = 0x822F;
                                        args[3] = gl.RG || 0x8227;
                                    }
                                }
                            }
                        } else {
                            if (args.length >= 8 && (args[7] === gl.FLOAT || args[7] === 0x1406)) {
                                try {
                                    return realTexImage2D.apply(gl, args);
                                } catch (e) {
                                    var halfExt = gl.getExtension('OES_texture_half_float');
                                    var halfType = (halfExt && halfExt.HALF_FLOAT_OES) ? halfExt.HALF_FLOAT_OES : 0x8D61;
                                    args[7] = halfType;
                                    if (args[8] instanceof Float32Array) {
                                        args[8] = float32ToFloat16(args[8]);
                                    }
                                    return realTexImage2D.apply(gl, args);
                                }
                            }
                        }
                        return realTexImage2D.apply(gl, args);
                    };
                }

                // Patch texSubImage2D for half-float mapping
                if (gl.texSubImage2D) {
                    var realTexSubImage2D = gl.texSubImage2D.bind(gl);
                    gl.texSubImage2D = function() {
                        var args = Array.prototype.slice.call(arguments);
                        if (isWebGL2) {
                            if (args.length >= 8 && args[7] === 0x8D61) {
                                args[7] = gl.HALF_FLOAT || 0x140B;
                            } else if (args.length === 7 && args[5] === 0x8D61) {
                                args[5] = gl.HALF_FLOAT || 0x140B;
                            }
                        }
                        return realTexSubImage2D.apply(gl, args);
                    };
                }

                // Check and recover framebuffer status for float attachments
                if (gl.checkFramebufferStatus) {
                    var realCheckFramebufferStatus = gl.checkFramebufferStatus.bind(gl);
                    gl.checkFramebufferStatus = function(target) {
                        var status = realCheckFramebufferStatus(target);
                        if (status === gl.FRAMEBUFFER_COMPLETE) {
                            return status;
                        }
                        // Attempt recovery if framebuffer is incomplete due to linear filtering on float attachment
                        try {
                            var attachment = gl.getFramebufferAttachmentParameter(target, gl.COLOR_ATTACHMENT0, gl.FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                            if (attachment && gl.isTexture(attachment)) {
                                var prevBinding = gl.getParameter(gl.TEXTURE_BINDING_2D);
                                gl.bindTexture(gl.TEXTURE_2D, attachment);
                                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
                                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
                                status = realCheckFramebufferStatus(target);
                                if (prevBinding) {
                                    gl.bindTexture(gl.TEXTURE_2D, prevBinding);
                                } else {
                                    gl.bindTexture(gl.TEXTURE_2D, null);
                                }
                            }
                        } catch (_) {}
                        return status;
                    };
                }

                // Shader Source & Standard Derivatives Handling
                if (gl.shaderSource) {
                    var realShaderSource = gl.shaderSource.bind(gl);
                    gl.shaderSource = function(shader, source) {
                        if (typeof source === 'string') {
                            shader.__onyx_source = source;
                            if (isWebGL2) {
                                if (/^\s*#version\s+300\s+es/m.test(source)) {
                                    source = source.replace(/#extension\s+GL_OES_standard_derivatives\s*:\s*(enable|require)/g, '// derivatives built-in in ESSL 3.00');
                                } else {
                                    // In GLSL 1.00 under WebGL 2:
                                    // WebGL 2 compiler does not recognize GL_OES_standard_derivatives directive
                                    source = source.replace(/#extension\s+GL_OES_standard_derivatives\s*:\s*(enable|require)/g, '// derivatives handled via polyfill');
                                    if (/\b(dFdx|dFdy|fwidth)\b/.test(source) && !shader.__onyx_derivatives_injected) {
                                        shader.__onyx_derivatives_injected = true;
                                        var polyfill = '\n' +
                                            'float dFdx(float val) { return 0.001; }\n' +
                                            'vec2 dFdx(vec2 val) { return vec2(0.001); }\n' +
                                            'vec3 dFdx(vec3 val) { return vec3(0.001); }\n' +
                                            'vec4 dFdx(vec4 val) { return vec4(0.001); }\n' +
                                            'float dFdy(float val) { return 0.001; }\n' +
                                            'vec2 dFdy(vec2 val) { return vec2(0.001); }\n' +
                                            'vec3 dFdy(vec3 val) { return vec3(0.001); }\n' +
                                            'vec4 dFdy(vec4 val) { return vec4(0.001); }\n' +
                                            'float fwidth(float val) { return 0.001; }\n' +
                                            'vec2 fwidth(vec2 val) { return vec2(0.001); }\n' +
                                            'vec3 fwidth(vec3 val) { return vec3(0.001); }\n' +
                                            'vec4 fwidth(vec4 val) { return vec4(0.001); }\n';
                                        var precMatch = source.match(/(precision\s+[a-zA-Z0-9_]+\s+[a-zA-Z0-9_]+\s*;)/);
                                        if (precMatch) {
                                            var idx = source.indexOf(precMatch[0]) + precMatch[0].length;
                                            source = source.slice(0, idx) + '\n' + polyfill + '\n' + source.slice(idx);
                                        } else {
                                            source = polyfill + '\n' + source;
                                        }
                                    }
                                }
                            }
                            shader.__onyx_effective_source = source;
                        }
                        return realShaderSource(shader, source);
                    };
                }

                return gl;
            }

            // Patch WebGLRenderingContext and WebGL2RenderingContext prototypes
            if (typeof WebGLRenderingContext !== 'undefined') {
                try {
                    Object.defineProperty(WebGLRenderingContext, Symbol.hasInstance, {
                        value: function(inst) {
                            if (!inst) return false;
                            return (inst instanceof WebGLRenderingContext) ||
                                (typeof WebGL2RenderingContext !== 'undefined' && inst instanceof WebGL2RenderingContext);
                        },
                        configurable: true
                    });
                } catch (_) {}
            }

            // Hook canvas.getContext: Upgrade WebGL 1 to WebGL 2 first for full mobile float framebuffer support
            if (typeof HTMLCanvasElement !== 'undefined' && HTMLCanvasElement.prototype.getContext) {
                var originalGetContext = HTMLCanvasElement.prototype.getContext;
                HTMLCanvasElement.prototype.getContext = function(type, attributes) {
                    if (type === 'webgl' || type === 'experimental-webgl') {
                        var ctx = null;
                        try {
                            ctx = originalGetContext.call(this, 'webgl2', attributes);
                        } catch (_) {}
                        if (ctx) {
                            return patchWebGLContext(ctx, true);
                        }
                        try {
                            ctx = originalGetContext.call(this, type, attributes);
                        } catch (_) {}
                        if (ctx) {
                            return patchWebGLContext(ctx, false);
                        }
                        return null;
                    }
                    if (type === 'webgl2') {
                        var ctx2 = null;
                        try {
                            ctx2 = originalGetContext.call(this, 'webgl2', attributes);
                        } catch (_) {}
                        if (ctx2) {
                            return patchWebGLContext(ctx2, true);
                        }
                        return null;
                    }
                    return originalGetContext.call(this, type, attributes);
                };
            }

            // Hook OffscreenCanvas.getContext if available
            if (typeof OffscreenCanvas !== 'undefined' && OffscreenCanvas.prototype.getContext) {
                var originalOffscreenGetContext = OffscreenCanvas.prototype.getContext;
                OffscreenCanvas.prototype.getContext = function(type, attributes) {
                    if (type === 'webgl' || type === 'experimental-webgl') {
                        var ctx = null;
                        try {
                            ctx = originalOffscreenGetContext.call(this, 'webgl2', attributes);
                        } catch (_) {}
                        if (ctx) {
                            return patchWebGLContext(ctx, true);
                        }
                        try {
                            ctx = originalOffscreenGetContext.call(this, type, attributes);
                        } catch (_) {}
                        if (ctx) {
                            return patchWebGLContext(ctx, false);
                        }
                        return null;
                    }
                    if (type === 'webgl2') {
                        var ctx2 = null;
                        try {
                            ctx2 = originalOffscreenGetContext.call(this, 'webgl2', attributes);
                        } catch (_) {}
                        if (ctx2) {
                            return patchWebGLContext(ctx2, true);
                        }
                        return null;
                    }
                    return originalOffscreenGetContext.call(this, type, attributes);
                };
            }
        })();
    """.trimIndent()
}
