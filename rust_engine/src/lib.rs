#![allow(deprecated)]

use adblock::cosmetic_filter_cache::ProceduralOrActionFilter;
use adblock::engine::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::regex_manager::RegexManagerDiscardPolicy;
use adblock::request::Request;
use adblock::resources::Resource;
use arc_swap::ArcSwapOption;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::{EnvUnowned, Outcome};
use std::collections::HashSet;
use std::panic::catch_unwind;
use std::ptr;
use std::sync::Arc;
use std::time::Duration;

static ENGINE: ArcSwapOption<Engine> = ArcSwapOption::const_empty();
static RESOURCES: ArcSwapOption<Vec<Resource>> = ArcSwapOption::const_empty();

fn configure_engine(engine: &mut Engine) {
    engine.set_regex_discard_policy(RegexManagerDiscardPolicy {
        cleanup_interval: Duration::from_secs(30),
        discard_unused_secs: Duration::from_secs(180),
    });
    let res_guard = RESOURCES.load();
    if let Some(ref res) = *res_guard {
        engine.use_resources(res.as_ref().clone());
    }
}

/// Normalizes uBlock Origin / AdGuard filter rule options into adblock-rust compatible syntax
/// so rules with `denyallow=`, `reason=`, `~popup`, `empty`, `mp4`, `fetch`, `strict3p`,
/// `strict1p`, `ehide`, `shide`, and `inline-script` are compiled rather than dropped.
fn normalize_filter_rules(rules_str: &str) -> String {
    let mut out = String::with_capacity(rules_str.len());

    for raw_line in rules_str.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('!') || line.starts_with('[') {
            out.push_str(line);
            out.push('\n');
            continue;
        }

        // Cosmetic / scriptlet / HTML filters pass through untouched
        if line.contains("##")
            || line.contains("#@#")
            || line.contains("#?#")
            || line.contains("#$#")
            || line.contains("#%#")
        {
            out.push_str(line);
            out.push('\n');
            continue;
        }

        let Some(dollar_idx) = line.rfind('$') else {
            out.push_str(line);
            out.push('\n');
            continue;
        };

        let pattern_part = &line[..dollar_idx];
        let options_part = &line[dollar_idx + 1..];
        if options_part.is_empty() {
            out.push_str(line);
            out.push('\n');
            continue;
        }

        let raw_opts: Vec<&str> = options_part.split(',').collect();
        let has_explicit_script = raw_opts.iter().any(|&o| o == "script" || o == "~script");
        let mut normalized_opts: Vec<String> = Vec::with_capacity(raw_opts.len());
        let mut drop_rule = false;

        for opt in raw_opts {
            let trimmed = opt.trim();
            if trimmed.is_empty() {
                continue;
            }
            match trimmed {
                "popup" | "popunder" => normalized_opts.push("doc".to_string()),
                "~popup" | "~popunder" => {
                    // adblock-rust excludes FROM_DOCUMENT by default on network rules
                }
                "elemhide" | "ehide" | "genericblock" | "shide" | "specifichide" => {
                    normalized_opts.push("generichide".to_string());
                }
                "empty" => normalized_opts.push("redirect=nooptext".to_string()),
                "mp4" => normalized_opts.push("redirect=noopmp4-1s".to_string()),
                "fetch" => normalized_opts.push("xhr".to_string()),
                "~fetch" => normalized_opts.push("~xhr".to_string()),
                "strict3p" => normalized_opts.push("3p".to_string()),
                "strict1p" => normalized_opts.push("1p".to_string()),
                "inline-script" => {
                    if !has_explicit_script {
                        normalized_opts.push(
                            "csp=script-src 'self' 'unsafe-eval' http: https: data: blob: mediastream: filesystem:"
                                .to_string(),
                        );
                    }
                }
                _ if trimmed.starts_with("denyallow=") => {
                    let domains = &trimmed["denyallow=".len()..];
                    let negated: Vec<String> = domains
                        .split('|')
                        .map(|d| d.trim())
                        .filter(|d| !d.is_empty())
                        .map(|d| {
                            if d.starts_with('~') {
                                d.to_string()
                            } else {
                                format!("~{}", d)
                            }
                        })
                        .collect();
                    if !negated.is_empty() {
                        normalized_opts.push(format!("to={}", negated.join("|")));
                    }
                }
                _ if trimmed.starts_with("reason=") => {
                    // Strip informational reason= metadata so the rule compiles
                }
                _ if trimmed.starts_with("urlskip=")
                    || trimmed.starts_with("uritransform=")
                    || trimmed.starts_with("replace=")
                    || trimmed.starts_with("permissions=") =>
                {
                    // Modifier options not supported by adblock-rust
                    drop_rule = true;
                    break;
                }
                _ => normalized_opts.push(trimmed.to_string()),
            }
        }

        if drop_rule {
            continue;
        }

        out.push_str(pattern_part);
        if !normalized_opts.is_empty() {
            out.push('$');
            out.push_str(&normalized_opts.join(","));
        }
        out.push('\n');
    }

    out
}

/// Initializes the adblock engine from a pre-compiled binary filter buffer.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_initEngine(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    buffer: JByteArray<'_>,
) -> jboolean {
    let outcome = env.with_env(|env| -> Result<jboolean, jni::errors::Error> {
        let bytes = match env.convert_byte_array(&buffer) {
            Ok(b) => b,
            Err(_) => return Ok(JNI_FALSE),
        };

        let mut engine = Engine::default();
        if engine.deserialize(&bytes).is_err() {
            return Ok(JNI_FALSE);
        }

        configure_engine(&mut engine);
        ENGINE.store(Some(Arc::new(engine)));
        Ok(JNI_TRUE)
    });

    match outcome.into_outcome() {
        Outcome::Ok(b) => b,
        _ => JNI_FALSE,
    }
}

/// Initializes or updates the engine from raw rule text and returns the compiled serialized bytes.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_initFromRules(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    rules: JString<'_>,
) -> jbyteArray {
    let outcome = env.with_env(|env| -> Result<jbyteArray, jni::errors::Error> {
        let rules_str: String = match env.get_string(&rules) {
            Ok(s) => s.into(),
            Err(_) => return Ok(ptr::null_mut()),
        };

        let normalized_rules = normalize_filter_rules(&rules_str);

        let mut filter_set = FilterSet::new(false);
        let parse_options = ParseOptions {
            permissions: adblock::resources::PermissionMask::from_bits(u8::MAX),
            ..Default::default()
        };
        filter_set.add_filter_list(normalized_rules, parse_options);
        let mut engine = Engine::new_with_filter_set(filter_set);

        let serialized = engine.serialize();
        configure_engine(&mut engine);
        ENGINE.store(Some(Arc::new(engine)));

        match env.byte_array_from_slice(&serialized) {
            Ok(arr) => Ok(arr.into_raw()),
            Err(_) => Ok(ptr::null_mut()),
        }
    });

    match outcome.into_outcome() {
        Outcome::Ok(arr) => arr,
        _ => ptr::null_mut(),
    }
}

/// Loads scriptlet/redirect resources from a JSON string (brave-resources.json format).
/// Can be called before or after initEngine() / initFromRules().
/// Returns JNI_TRUE on success, JNI_FALSE on failure.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_loadResources(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    resources_json: JString<'_>,
) -> jboolean {
    let outcome = env.with_env(|env| -> Result<jboolean, jni::errors::Error> {
        let json_str: String = match env.get_string(&resources_json) {
            Ok(s) => s.into(),
            Err(_) => return Ok(JNI_FALSE),
        };

        // Parse Vec<Resource> from the brave-resources.json format
        let resources: Vec<Resource> = match serde_json::from_str(&json_str) {
            Ok(r) => r,
            Err(_) => return Ok(JNI_FALSE),
        };

        RESOURCES.store(Some(Arc::new(resources.clone())));

        // If an Engine is already initialized, update its resource storage in-place if possible
        if let Some(mut arc_engine) = ENGINE.swap(None) {
            if let Some(engine_mut) = Arc::get_mut(&mut arc_engine) {
                engine_mut.use_resources(resources);
                ENGINE.store(Some(arc_engine));
                return Ok(JNI_TRUE);
            } else {
                // Concurrent reader held an Arc clone; restore and deserialize cleanly
                let serialized = arc_engine.serialize();
                ENGINE.store(Some(arc_engine));
                let mut new_engine = Engine::default();
                if new_engine.deserialize(&serialized).is_ok() {
                    configure_engine(&mut new_engine);
                    ENGINE.store(Some(Arc::new(new_engine)));
                    return Ok(JNI_TRUE);
                }
                return Ok(JNI_FALSE);
            }
        }

        // Engine not yet constructed; configure_engine() will attach RESOURCES when initEngine/initFromRules runs
        Ok(JNI_TRUE)
    });

    match outcome.into_outcome() {
        Outcome::Ok(b) => b,
        _ => JNI_FALSE,
    }
}

/// Legacy checkUrl using String resource_type.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_checkUrl(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
    source_url: JString<'_>,
    resource_type: JString<'_>,
) -> jboolean {
    let outcome = env.with_env(|env| -> Result<jboolean, jni::errors::Error> {
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => return Ok(JNI_FALSE),
        };
        if url_str.is_empty() {
            return Ok(JNI_FALSE);
        }

        let source_str: String = match env.get_string(&source_url) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let type_str: String = match env.get_string(&resource_type) {
            Ok(s) => {
                let st: String = s.into();
                if st == "popup" {
                    "main_frame".to_string()
                } else {
                    st
                }
            }
            Err(_) => "other".to_string(),
        };

        let engine_guard = ENGINE.load();
        if let Some(ref engine) = *engine_guard {
            if let Ok(request) = Request::new(&url_str, &source_str, &type_str, "GET") {
                let blocker_result = engine.check_network_request(&request);
                if blocker_result.should_block() {
                    return Ok(JNI_TRUE);
                }
            }
        }

        Ok(JNI_FALSE)
    });

    match outcome.into_outcome() {
        Outcome::Ok(b) => b,
        _ => JNI_FALSE,
    }
}

/// High-performance request checking using an integer resource type and lock-free engine reads.
/// Returns:
/// - null: request is allowed
/// - "blocked": request is blocked
/// - "redirect:<data_or_url>": request is matched with a surrogate script/redirect
/// - "rewrite:<new_url>": request is rewritten to a clean or redirect URL
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_checkRequestNative(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
    source_url: JString<'_>,
    resource_type: jint,
    method: JString<'_>,
) -> jstring {
    let outcome = env.with_env(|env| -> Result<jstring, jni::errors::Error> {
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => return Ok(ptr::null_mut()),
        };
        if url_str.is_empty() {
            return Ok(ptr::null_mut());
        }

        let source_str: String = match env.get_string(&source_url) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        let method_str: String = match env.get_string(&method) {
            Ok(s) => {
                let m: String = s.into();
                if m.is_empty() {
                    "GET".to_string()
                } else {
                    m
                }
            }
            Err(_) => "GET".to_string(),
        };

        let type_str = match resource_type {
            1 => "script",
            2 => "image",
            3 => "stylesheet",
            4 => "sub_frame",
            5 => "xhr",
            6 => "media",
            7 => "main_frame",
            8 => "font",
            9 => "ping",
            10 => "websocket",
            11 => "object",
            12 => "main_frame",
            _ => "other",
        };

        let engine_guard = ENGINE.load();
        if let Some(ref engine) = *engine_guard {
            if let Ok(request) = Request::new(&url_str, &source_str, type_str, &method_str) {
                let blocker_result = engine.check_network_request(&request);
                if let Some(ref redirect) = blocker_result.redirect {
                    if !redirect.is_empty() {
                        return match env.new_string(format!("redirect:{}", redirect)) {
                            Ok(s) => Ok(s.into_raw()),
                            Err(_) => Ok(ptr::null_mut()),
                        };
                    }
                }
                if let Some(ref rewrite) = blocker_result.rewritten_url {
                    if !rewrite.is_empty() {
                        return match env.new_string(format!("rewrite:{}", rewrite)) {
                            Ok(s) => Ok(s.into_raw()),
                            Err(_) => Ok(ptr::null_mut()),
                        };
                    }
                }
                if blocker_result.should_block() {
                    return match env.new_string("blocked") {
                        Ok(s) => Ok(s.into_raw()),
                        Err(_) => Ok(ptr::null_mut()),
                    };
                }
            }
        }

        Ok(ptr::null_mut())
    });

    match outcome.into_outcome() {
        Outcome::Ok(s) => s,
        _ => ptr::null_mut(),
    }
}

/// Returns Content Security Policy directives matching this request, or null if none.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_getCspDirectivesNative(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
    source_url: JString<'_>,
    resource_type: jint,
    method: JString<'_>,
) -> jstring {
    let outcome = env.with_env(|env| -> Result<jstring, jni::errors::Error> {
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => return Ok(ptr::null_mut()),
        };
        if url_str.is_empty() {
            return Ok(ptr::null_mut());
        }

        let source_str: String = match env.get_string(&source_url) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        let method_str: String = match env.get_string(&method) {
            Ok(s) => {
                let m: String = s.into();
                if m.is_empty() {
                    "GET".to_string()
                } else {
                    m
                }
            }
            Err(_) => "GET".to_string(),
        };

        let type_str = match resource_type {
            1 => "script",
            2 => "image",
            3 => "stylesheet",
            4 => "sub_frame",
            5 => "xhr",
            6 => "media",
            7 => "main_frame",
            8 => "font",
            9 => "ping",
            10 => "websocket",
            11 => "object",
            12 => "main_frame",
            _ => "other",
        };

        let engine_guard = ENGINE.load();
        if let Some(ref engine) = *engine_guard {
            if let Ok(request) = Request::new(&url_str, &source_str, type_str, &method_str) {
                if let Some(csp) = engine.get_csp_directives(&request) {
                    if !csp.is_empty() {
                        return match env.new_string(csp) {
                            Ok(s) => Ok(s.into_raw()),
                            Err(_) => Ok(ptr::null_mut()),
                        };
                    }
                }
            }
        }

        Ok(ptr::null_mut())
    });

    match outcome.into_outcome() {
        Outcome::Ok(s) => s,
        _ => ptr::null_mut(),
    }
}

/// Returns a JSON object with cosmetic filter resources for the given URL.
/// JSON format: {"css":"...","script":"...","generichide":<bool>,"exceptions":[...],"procedural":[...]}
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_getCosmeticResources(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
) -> jstring {
    let outcome = env.with_env(|env| -> Result<jstring, jni::errors::Error> {
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        let mut css = String::new();
        let mut script = String::new();
        let mut generichide = false;
        let mut exceptions_vec: Vec<String> = Vec::new();
        let mut procedural_vec: Vec<String> = Vec::new();

        if !url_str.is_empty() {
            let engine_guard = ENGINE.load();
            if let Some(ref engine) = *engine_guard {
                let resources = engine.url_cosmetic_resources(&url_str);

                // Build CSS from hide_selectors
                if !resources.hide_selectors.is_empty() {
                    let selectors: Vec<&str> =
                        resources.hide_selectors.iter().map(|s| s.as_str()).collect();
                    css = format!("{} {{ display: none !important; }}", selectors.join(", "));
                }

                // Promote pure-CSS :style(...) rules in procedural_actions to instant document_start CSS
                for action_json in resources.procedural_actions {
                    if let Ok(parsed_filter) =
                        serde_json::from_str::<ProceduralOrActionFilter>(&action_json)
                    {
                        if let Some((selector, style)) = parsed_filter.as_css() {
                            if !css.is_empty() {
                                css.push('\n');
                            }
                            css.push_str(&selector);
                            css.push_str(" { ");
                            css.push_str(&style);
                            css.push_str(" }");
                            continue;
                        }
                    }
                    procedural_vec.push(action_json);
                }

                // Get scriptlet JS code (compiled from +js() rules using loaded resources)
                script = resources.injected_script;

                // Propagate generichide flag
                generichide = resources.generichide;

                // Propagate exceptions for generic rules
                exceptions_vec = resources.exceptions.into_iter().collect();
            }
        }

        let css_val = serde_json::Value::String(css);
        let script_val = serde_json::Value::String(script);
        let exceptions_val =
            serde_json::to_value(&exceptions_vec).unwrap_or(serde_json::Value::Array(Vec::new()));
        let procedural_val =
            serde_json::to_value(&procedural_vec).unwrap_or(serde_json::Value::Array(Vec::new()));
        let json_out = format!(
            "{{\"css\":{},\"script\":{},\"generichide\":{},\"exceptions\":{},\"procedural\":{}}}",
            css_val, script_val, generichide, exceptions_val, procedural_val
        );

        match env.new_string(&json_out) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => match env.new_string("{\"css\":\"\",\"script\":\"\",\"generichide\":false,\"exceptions\":[],\"procedural\":[]}") {
                Ok(s) => Ok(s.into_raw()),
                Err(_) => Ok(ptr::null_mut()),
            },
        }
    });

    match outcome.into_outcome() {
        Outcome::Ok(s) => s,
        _ => ptr::null_mut(),
    }
}

/// Queries the active adblock engine for generic CSS hide selectors matching
/// the provided DOM classes and IDs, respecting exceptions.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_getHiddenClassIdSelectors(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    classes_json: JString<'_>,
    ids_json: JString<'_>,
    exceptions_json: JString<'_>,
) -> jstring {
    let outcome = env.with_env(|env| -> Result<jstring, jni::errors::Error> {
        let classes_str: String = match env.get_string(&classes_json) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let ids_str: String = match env.get_string(&ids_json) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let exceptions_str: String = match env.get_string(&exceptions_json) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        let classes: Vec<String> = if classes_str.is_empty() {
            Vec::new()
        } else {
            serde_json::from_str(&classes_str).unwrap_or_default()
        };

        let ids: Vec<String> = if ids_str.is_empty() {
            Vec::new()
        } else {
            serde_json::from_str(&ids_str).unwrap_or_default()
        };

        let exceptions_vec: Vec<String> = if exceptions_str.is_empty() {
            Vec::new()
        } else {
            serde_json::from_str(&exceptions_str).unwrap_or_default()
        };
        let exceptions: HashSet<String> = exceptions_vec.into_iter().collect();

        let json_out = {
            let engine_guard = ENGINE.load();
            if let Some(ref engine) = *engine_guard {
                let matching = engine.hidden_class_id_selectors(&classes, &ids, &exceptions);
                serde_json::to_string(&matching).unwrap_or_else(|_| "[]".to_string())
            } else {
                "[]".to_string()
            }
        };

        match env.new_string(&json_out) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => match env.new_string("[]") {
                Ok(s) => Ok(s.into_raw()),
                Err(_) => Ok(ptr::null_mut()),
            },
        }
    });

    match outcome.into_outcome() {
        Outcome::Ok(s) => s,
        _ => ptr::null_mut(),
    }
}

/// Serializes the current active engine to a byte array.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_serializeEngine(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jbyteArray {
    let outcome = env.with_env(|env| -> Result<jbyteArray, jni::errors::Error> {
        let engine_guard = ENGINE.load();
        if let Some(ref engine) = *engine_guard {
            let serialized = engine.serialize();
            return match env.byte_array_from_slice(&serialized) {
                Ok(arr) => Ok(arr.into_raw()),
                Err(_) => Ok(ptr::null_mut()),
            };
        }
        Ok(ptr::null_mut())
    });

    match outcome.into_outcome() {
        Outcome::Ok(arr) => arr,
        _ => ptr::null_mut(),
    }
}

/// Checks whether the adblock engine is loaded and active.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_isEngineInitialized(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jboolean {
    let result = catch_unwind(|| {
        let engine_guard = ENGINE.load();
        if engine_guard.is_some() {
            return JNI_TRUE;
        }
        JNI_FALSE
    });

    result.unwrap_or(JNI_FALSE)
}
