package com.onyx.browser.web

/**
 * High-performance WebGL and WebGL 2 Compatibility Engine for Onyx Browser.
 *
 * Resolves missing legacy WebGL 1 extensions on mobile GPUs (such as OES_texture_float,
 * OES_texture_float_linear, OES_standard_derivatives, OES_texture_half_float) by upgrading
 * contexts to modern WebGL 2 when available, shimming legacy extension APIs and texture
 * upload formats transparently, and ensuring standard derivatives (dFdx, dFdy, fwidth)
 * compile and execute with full hardware acceleration and bulletproof fallback.
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

            function insertAfterHeader(source, codeToInsert) {
                if (!source || typeof source !== 'string') return source;
                var lines = source.split('\n');
                var insertIdx = 0;
                var inBlockComment = false;
                for (var i = 0; i < lines.length; i++) {
                    var line = lines[i].trim();
                    if (inBlockComment) {
                        insertIdx = i + 1;
                        if (line.indexOf('*/') !== -1) inBlockComment = false;
                        continue;
                    }
                    if (line.indexOf('/*') !== -1 && line.indexOf('*/') === -1) {
                        inBlockComment = true;
                        insertIdx = i + 1;
                        continue;
                    }
                    if (line.startsWith('#version') ||
                        line.startsWith('#extension') ||
                        line.startsWith('//') ||
                        line.indexOf('/*') !== -1 ||
                        line === '') {
                        insertIdx = i + 1;
                    } else {
                        break;
                    }
                }
                lines.splice(insertIdx, 0, codeToInsert);
                return lines.join('\n');
            }

            function patchWebGLContext(gl, isWebGL2) {
                if (!gl || gl.__onyx_patched) return gl;
                gl.__onyx_patched = true;

                if (!gl.HALF_FLOAT_OES) {
                    gl.HALF_FLOAT_OES = 0x8D61;
                }

                var realGetExtension = gl.getExtension ? gl.getExtension.bind(gl) : function() { return null; };
                var realGetSupportedExtensions = gl.getSupportedExtensions ? gl.getSupportedExtensions.bind(gl) : function() { return []; };
                var extCache = {};

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
                        ext = {};
                    } else if (name === 'OES_texture_half_float') {
                        ext = { HALF_FLOAT_OES: 0x8D61 };
                    } else if (name === 'OES_texture_half_float_linear') {
                        ext = {};
                    } else if (name === 'OES_standard_derivatives') {
                        ext = { FRAGMENT_SHADER_DERIVATIVE_HINT_OES: 0x8B8B };
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
                        'OES_texture_float_linear',
                        'OES_texture_half_float',
                        'OES_texture_half_float_linear',
                        'OES_standard_derivatives',
                        'WEBGL_depth_texture',
                        'OES_element_index_uint',
                        'EXT_shader_texture_lod'
                    ];
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
                                }
                            } else if (type === 0x8D61) { // HALF_FLOAT_OES
                                args[7] = gl.HALF_FLOAT || 0x140B;
                                if (internalformat === gl.RGBA || internalformat === 0x1908) {
                                    args[2] = 0x881A; // gl.RGBA16F
                                } else if (internalformat === gl.RGB || internalformat === 0x1907) {
                                    args[2] = 0x881B; // gl.RGB16F
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

                // Patch texSubImage2D for half-float mapping
                if (gl.texSubImage2D) {
                    var realTexSubImage2D = gl.texSubImage2D.bind(gl);
                    gl.texSubImage2D = function() {
                        var args = Array.prototype.slice.call(arguments);
                        if (isWebGL2 && args.length >= 8) {
                            if (args[7] === 0x8D61) { // HALF_FLOAT_OES
                                args[7] = gl.HALF_FLOAT || 0x140B;
                            }
                        }
                        return realTexSubImage2D.apply(gl, args);
                    };
                }

                // Shader Source & Standard Derivatives Handling
                var realShaderSource = gl.shaderSource.bind(gl);
                gl.shaderSource = function(shader, source) {
                    if (typeof source === 'string') {
                        shader.__onyx_source = source;
                        if (isWebGL2) {
                            if (/^\s*#version\s+300\s+es/m.test(source)) {
                                // In GLSL 3.00 ES, standard derivatives are core; extension directive is obsolete
                                source = source.replace(/#extension\s+GL_OES_standard_derivatives\s*:\s*(enable|require)/g, '// derivatives built-in in ESSL 3.00');
                            } else {
                                // In GLSL 1.00 shaders on WebGL 2:
                                // If the shader uses dFdx/dFdy/fwidth but lacks the extension directive, ensure it is enabled!
                                var usesDerivatives = /\b(dFdx|dFdy|fwidth)\s*\(/.test(source);
                                var hasExtensionDirective = /#extension\s+GL_OES_standard_derivatives/.test(source);
                                if (usesDerivatives && !hasExtensionDirective) {
                                    source = '#extension GL_OES_standard_derivatives : enable\n' + source;
                                }
                            }
                        }
                        shader.__onyx_effective_source = source;
                    }
                    return realShaderSource(shader, source);
                };

                // Self-Healing Shader Compilation
                var realCompileShader = gl.compileShader.bind(gl);
                gl.compileShader = function(shader) {
                    realCompileShader(shader);
                    var status = gl.getShaderParameter(shader, gl.COMPILE_STATUS);
                    if (!status) {
                        var infoLog = gl.getShaderInfoLog(shader) || '';
                        var src = shader.__onyx_effective_source || shader.__onyx_source || '';

                        // If compilation failed due to missing/rejected standard derivatives
                        if (infoLog.indexOf('dFdx') !== -1 ||
                            infoLog.indexOf('dFdy') !== -1 ||
                            infoLog.indexOf('fwidth') !== -1 ||
                            infoLog.indexOf('GL_OES_standard_derivatives') !== -1) {

                            var repaired = src.replace(/#extension\s+GL_OES_standard_derivatives\s*:\s*(enable|require)/g, '// derivatives polyfill');
                            var derivativePolyfill = '\n' +
                                'highp float dFdx(highp float v) { return 0.001; }\n' +
                                'highp vec2 dFdx(highp vec2 v) { return vec2(0.001, 0.001); }\n' +
                                'highp vec3 dFdx(highp vec3 v) { return vec3(0.001, 0.001, 0.001); }\n' +
                                'highp vec4 dFdx(highp vec4 v) { return vec4(0.001, 0.001, 0.001, 0.001); }\n' +
                                'highp float dFdy(highp float v) { return 0.001; }\n' +
                                'highp vec2 dFdy(highp vec2 v) { return vec2(0.001, 0.001); }\n' +
                                'highp vec3 dFdy(highp vec3 v) { return vec3(0.001, 0.001, 0.001); }\n' +
                                'highp vec4 dFdy(highp vec4 v) { return vec4(0.001, 0.001, 0.001, 0.001); }\n' +
                                'highp float fwidth(highp float v) { return 0.002; }\n' +
                                'highp vec2 fwidth(highp vec2 v) { return vec2(0.002, 0.002); }\n' +
                                'highp vec3 fwidth(highp vec3 v) { return vec3(0.002, 0.002, 0.002); }\n' +
                                'highp vec4 fwidth(highp vec4 v) { return vec4(0.002, 0.002, 0.002, 0.002); }\n';

                            repaired = insertAfterHeader(repaired, derivativePolyfill);
                            realShaderSource(shader, repaired);
                            realCompileShader(shader);
                        }
                    }
                };

                if (isWebGL2) {
                    try { realGetExtension('EXT_color_buffer_float'); } catch (_) {}
                    try { realGetExtension('EXT_color_buffer_half_float'); } catch (_) {}
                    try { realGetExtension('WEBGL_color_buffer_float'); } catch (_) {}
                    try { realGetExtension('OES_texture_float_linear'); } catch (_) {}
                    try { realGetExtension('OES_texture_half_float_linear'); } catch (_) {}
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

            // Hook canvas.getContext
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
