use adblock::engine::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::Resource;
use arc_swap::ArcSwapOption;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashSet;
use std::panic::catch_unwind;
use std::ptr;
use std::sync::Arc;

static ENGINE: ArcSwapOption<Engine> = ArcSwapOption::const_empty();

/// Initializes the adblock engine from a pre-compiled binary filter buffer.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_initEngine(
    mut env: JNIEnv,
    _class: JClass,
    buffer: JByteArray,
) -> jboolean {
    let result = catch_unwind(move || {
        let bytes = match env.convert_byte_array(&buffer) {
            Ok(b) => b,
            Err(_) => return JNI_FALSE,
        };

        let mut engine = Engine::default();
        if engine.deserialize(&bytes).is_err() {
            return JNI_FALSE;
        }

        ENGINE.store(Some(Arc::new(engine)));
        JNI_TRUE
    });

    result.unwrap_or(JNI_FALSE)
}

/// Initializes or updates the engine from raw rule text and returns the compiled serialized bytes.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_initFromRules(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
) -> jbyteArray {
    let result = catch_unwind(move || {
        let rules_str: String = match env.get_string(&rules) {
            Ok(s) => s.into(),
            Err(_) => return ptr::null_mut(),
        };

        let mut filter_set = FilterSet::new(false);
        filter_set.add_filter_list(rules_str, ParseOptions::default());
        let engine = Engine::new_with_filter_set(filter_set);

        let serialized = engine.serialize();
        ENGINE.store(Some(Arc::new(engine)));

        match env.byte_array_from_slice(&serialized) {
            Ok(arr) => arr.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    });

    result.unwrap_or(ptr::null_mut())
}

/// Loads scriptlet/redirect resources from a JSON string (brave-resources.json format).
/// Must be called after initEngine() or initFromRules() to enable scriptlet injection.
/// Returns JNI_TRUE on success, JNI_FALSE on failure.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_loadResources(
    mut env: JNIEnv,
    _class: JClass,
    resources_json: JString,
) -> jboolean {
    let result = catch_unwind(move || {
        let json_str: String = match env.get_string(&resources_json) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };

        // Parse Vec<Resource> from the brave-resources.json format
        let resources: Vec<Resource> = match serde_json::from_str(&json_str) {
            Ok(r) => r,
            Err(_) => return JNI_FALSE,
        };

        let current_guard = ENGINE.load();
        if let Some(ref current_engine) = *current_guard {
            let serialized = current_engine.serialize();
            let mut new_engine = Engine::default();
            if new_engine.deserialize(&serialized).is_ok() {
                new_engine.use_resources(resources);
                ENGINE.store(Some(Arc::new(new_engine)));
                return JNI_TRUE;
            }
        }

        JNI_FALSE
    });

    result.unwrap_or(JNI_FALSE)
}

/// Legacy checkUrl using String resource_type.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_checkUrl(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source_url: JString,
    resource_type: JString,
) -> jboolean {
    let result = catch_unwind(move || {
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        if url_str.is_empty() {
            return JNI_FALSE;
        }

        let source_str: String = match env.get_string(&source_url) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let type_str: String = match env.get_string(&resource_type) {
            Ok(s) => s.into(),
            Err(_) => "other".to_string(),
        };

        let engine_guard = ENGINE.load();
        if let Some(ref engine) = *engine_guard {
            if let Ok(request) = Request::new(&url_str, &source_str, &type_str, "GET") {
                let blocker_result = engine.check_network_request(&request);
                if blocker_result.should_block() {
                    return JNI_TRUE;
                }
            }
        }

        JNI_FALSE
    });

    result.unwrap_or(JNI_FALSE)
}

/// High-performance request checking using an integer resource type and lock-free engine reads.
/// Returns:
/// - null: request is allowed
/// - "blocked": request is blocked
/// - "redirect:<data_or_url>": request is matched with a surrogate script/redirect
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_checkRequestNative(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source_url: JString,
    resource_type: jint,
) -> jstring {
    let result = catch_unwind(move || {
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => return ptr::null_mut(),
        };
        if url_str.is_empty() {
            return ptr::null_mut();
        }

        let source_str: String = match env.get_string(&source_url) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        let type_str = match resource_type {
            1 => "script",
            2 => "image",
            3 => "stylesheet",
            4 => "sub_frame",
            5 => "xhr",
            6 => "media",
            7 => "main_frame",
            _ => "other",
        };

        let engine_guard = ENGINE.load();
        if let Some(ref engine) = *engine_guard {
            if let Ok(request) = Request::new(&url_str, &source_str, type_str, "GET") {
                let blocker_result = engine.check_network_request(&request);
                if let Some(ref redirect) = blocker_result.redirect {
                    if !redirect.is_empty() {
                        return match env.new_string(format!("redirect:{}", redirect)) {
                            Ok(s) => s.into_raw(),
                            Err(_) => ptr::null_mut(),
                        };
                    }
                }
                if blocker_result.should_block() {
                    return match env.new_string("blocked") {
                        Ok(s) => s.into_raw(),
                        Err(_) => ptr::null_mut(),
                    };
                }
            }
        }

        ptr::null_mut()
    });

    result.unwrap_or(ptr::null_mut())
}

/// Returns a JSON object with cosmetic filter resources for the given URL.
/// JSON format: {"css":"...","script":"...","generichide":<bool>,"exceptions":[...],"procedural":[...]}
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_getCosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let result = catch_unwind(std::panic::AssertUnwindSafe(|| {
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

                // Get scriptlet JS code (compiled from +js() rules using loaded resources)
                script = resources.injected_script;

                // Propagate generichide flag
                generichide = resources.generichide;

                // Propagate exceptions for generic rules
                exceptions_vec = resources.exceptions.into_iter().collect();

                // Propagate procedural actions
                procedural_vec = resources.procedural_actions.into_iter().collect();
            }
        }

        let css_val = serde_json::Value::String(css);
        let script_val = serde_json::Value::String(script);
        let exceptions_val = serde_json::to_value(&exceptions_vec).unwrap_or(serde_json::Value::Array(Vec::new()));
        let procedural_val = serde_json::to_value(&procedural_vec).unwrap_or(serde_json::Value::Array(Vec::new()));
        format!(
            "{{\"css\":{},\"script\":{},\"generichide\":{},\"exceptions\":{},\"procedural\":{}}}",
            css_val,
            script_val,
            generichide,
            exceptions_val,
            procedural_val
        )
    }));

    let json_out = result.unwrap_or_else(|_| "{\"css\":\"\",\"script\":\"\",\"generichide\":false,\"exceptions\":[],\"procedural\":[]}".to_string());
    match env.new_string(json_out) {
        Ok(s) => s.into_raw(),
        Err(_) => env
            .new_string("{\"css\":\"\",\"script\":\"\",\"generichide\":false,\"exceptions\":[],\"procedural\":[]}")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut()),
    }
}

/// Queries the active adblock engine for generic CSS hide selectors matching
/// the provided DOM classes and IDs, respecting exceptions.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_getHiddenClassIdSelectors(
    mut env: JNIEnv,
    _class: JClass,
    classes_json: JString,
    ids_json: JString,
    exceptions_json: JString,
) -> jstring {
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

    let result = catch_unwind(std::panic::AssertUnwindSafe(|| {
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

        let engine_guard = ENGINE.load();
        if let Some(ref engine) = *engine_guard {
            let matching = engine.hidden_class_id_selectors(&classes, &ids, &exceptions);
            return serde_json::to_string(&matching).unwrap_or_else(|_| "[]".to_string());
        }
        "[]".to_string()
    }));

    let json_out = result.unwrap_or_else(|_| "[]".to_string());
    match env.new_string(json_out) {
        Ok(s) => s.into_raw(),
        Err(_) => env
            .new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut()),
    }
}

/// Serializes the current active engine to a byte array.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_serializeEngine(
    mut env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    let result = catch_unwind(move || {
        let engine_guard = ENGINE.load();
        if let Some(ref engine) = *engine_guard {
            let serialized = engine.serialize();
            return match env.byte_array_from_slice(&serialized) {
                Ok(arr) => arr.into_raw(),
                Err(_) => ptr::null_mut(),
            };
        }
        ptr::null_mut()
    });

    result.unwrap_or(ptr::null_mut())
}

/// Checks whether the adblock engine is loaded and active.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_isEngineInitialized(
    _env: JNIEnv,
    _class: JClass,
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
