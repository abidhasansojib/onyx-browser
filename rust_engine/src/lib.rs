use adblock::engine::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::panic::catch_unwind;
use std::ptr;
use std::sync::RwLock;

static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

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

        if let Ok(mut lock) = ENGINE.write() {
            *lock = Some(engine);
            JNI_TRUE
        } else {
            JNI_FALSE
        }
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

        if let Ok(mut lock) = ENGINE.write() {
            *lock = Some(engine);
        }

        match env.byte_array_from_slice(&serialized) {
            Ok(arr) => arr.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    });

    result.unwrap_or(ptr::null_mut())
}

/// Checks if a network request to `url` originating from `source_url` with `resource_type` should be blocked.
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
        let source_str: String = match env.get_string(&source_url) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        let type_str: String = match env.get_string(&resource_type) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };

        if let Ok(lock) = ENGINE.read() {
            if let Some(ref engine) = *lock {
                if let Ok(request) = Request::new(&url_str, &source_str, &type_str, "GET") {
                    let blocker_result = engine.check_network_request(&request);
                    if blocker_result.should_block() {
                        return JNI_TRUE;
                    }
                }
            }
        }

        JNI_FALSE
    });

    result.unwrap_or(JNI_FALSE)
}

/// Returns CSS selectors formatted as a stylesheet to collapse and hide ad elements for the given URL.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_getCosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let result = catch_unwind(move || {
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        let mut css = String::new();
        if !url_str.is_empty() {
            if let Ok(lock) = ENGINE.read() {
                if let Some(ref engine) = *lock {
                    let resources = engine.url_cosmetic_resources(&url_str);
                    if !resources.hide_selectors.is_empty() {
                        let selectors: Vec<&str> =
                            resources.hide_selectors.iter().map(|s| s.as_str()).collect();
                        css = format!("{} {{ display: none !important; }}", selectors.join(", "));
                    }
                }
            }
        }

        match env.new_string(css) {
            Ok(s) => s.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    });

    result.unwrap_or(ptr::null_mut())
}

/// Serializes the current active engine to a byte array.
#[no_mangle]
pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_serializeEngine(
    mut env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    let result = catch_unwind(move || {
        if let Ok(lock) = ENGINE.read() {
            if let Some(ref engine) = *lock {
                let serialized = engine.serialize();
                return match env.byte_array_from_slice(&serialized) {
                    Ok(arr) => arr.into_raw(),
                    Err(_) => ptr::null_mut(),
                };
            }
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
        if let Ok(lock) = ENGINE.read() {
            if lock.is_some() {
                return JNI_TRUE;
            }
        }
        JNI_FALSE
    });

    result.unwrap_or(JNI_FALSE)
}
