//! XISO Tool — núcleo Rust para Android (cdylib + JNI).
//!
//! Expõe para o Kotlin:
//!   - `extractIso(isoPath, outDir, callback) -> opId`
//!   - `createIso(srcDir, outIso, callback) -> opId`
//!   - `cancel(opId)`
//!   - `checkXiso(isoPath) -> Boolean`
//!
//! O callback Kotlin precisa implementar:
//!   - `onProgress(processedBytes: Long, totalBytes: Long, currentFile: String?)`
//!   - `onFinished(errorCode: Int, message: String?)`   // 0 = sucesso
//!
//! Cada operação roda em uma thread própria do Rust (attach_current_thread para
//! chamar de volta a JVM). O progresso é publicado em estado compartilhado por
//! atomics e uma thread observadora emite os callbacks JNI com throttle, o que
//! evita compartilhar `JNIEnv` entre threads. Pânico nenhum cruza a fronteira
//! JNI: tudo passa por catch_unwind e vira erro estruturado.

mod error;
mod ops;

use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::{JNIEnv, JavaVM};

use error::{XisoError, ERR_UNKNOWN};
use ops::{OpKind, SharedProgress};

static JAVA_VM: OnceLock<JavaVM> = OnceLock::new();
static NEXT_OP_ID: AtomicI64 = AtomicI64::new(1);
static ACTIVE_OPS: OnceLock<Mutex<HashMap<i64, Arc<AtomicBool>>>> = OnceLock::new();

const PROGRESS_MIN_INTERVAL_MS: u128 = 150;

fn active_ops() -> &'static Mutex<HashMap<i64, Arc<AtomicBool>>> {
    ACTIVE_OPS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(|s| s.into())
}

fn start_task<'local>(
    env: &mut JNIEnv<'local>,
    src: JString<'local>,
    dst: JString<'local>,
    callback: JObject<'local>,
    kind: OpKind,
) -> jlong {
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return -1,
    };
    let _ = JAVA_VM.set(vm);

    let src = match read_string(env, &src) {
        Some(s) => s,
        None => return -1,
    };
    let dst = match read_string(env, &dst) {
        Some(s) => s,
        None => return -1,
    };
    let callback: GlobalRef = match env.new_global_ref(callback) {
        Ok(g) => g,
        Err(_) => return -1,
    };

    let op_id = NEXT_OP_ID.fetch_add(1, Ordering::SeqCst);
    let cancel = Arc::new(AtomicBool::new(false));
    if let Ok(mut ops) = active_ops().lock() {
        ops.insert(op_id, cancel.clone());
    }

    let spawned = std::thread::Builder::new()
        .name("xiso-task".to_string())
        .spawn(move || {
            run_task(kind, src, dst, callback, cancel);
            if let Ok(mut ops) = active_ops().lock() {
                ops.remove(&op_id);
            }
        });

    match spawned {
        Ok(_) => op_id,
        Err(_) => {
            if let Ok(mut ops) = active_ops().lock() {
                ops.remove(&op_id);
            }
            -1
        }
    }
}

/// Thread observadora: emite onProgress periodicamente a partir do estado compartilhado.
fn spawn_progress_watcher(callback: GlobalRef, shared: Arc<SharedProgress>) {
    let _ = std::thread::Builder::new()
        .name("xiso-progress".to_string())
        .spawn(move || {
            let Some(vm) = JAVA_VM.get() else {
                return;
            };
            let Ok(mut attached) = vm.attach_current_thread() else {
                return;
            };
            let env: &mut JNIEnv = &mut attached;

            let mut last_emit = Instant::now() - Duration::from_millis(10_000);
            let mut last_file = String::new();
            while !shared.done.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(100));
                let (processed, total, file) = shared.snapshot();
                let file_changed = !file.is_empty() && file != last_file;
                if !file_changed && last_emit.elapsed().as_millis() < PROGRESS_MIN_INTERVAL_MS {
                    continue;
                }
                last_emit = Instant::now();
                if !file.is_empty() {
                    last_file = file.clone();
                }
                report_progress(env, &callback, processed, total, &file);
            }

            // Relatório final (100% ou valor consolidado).
            let (processed, total, file) = shared.snapshot();
            report_progress(env, &callback, processed, total, &file);
        });
}

fn run_task(kind: OpKind, src: String, dst: String, callback: GlobalRef, cancel: Arc<AtomicBool>) {
    let vm = match JAVA_VM.get() {
        Some(vm) => vm,
        None => return,
    };

    let shared = Arc::new(SharedProgress::new());
    spawn_progress_watcher(callback.clone(), shared.clone());

    let task_shared = shared.clone();
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| match kind {
        OpKind::Extract => ops::extract_iso(Path::new(&src), Path::new(&dst), &cancel, &task_shared),
        OpKind::Create => ops::create_iso(Path::new(&src), Path::new(&dst), cancel.clone(), task_shared.clone()),
    }));

    let result: Result<(), XisoError> = match outcome {
        Ok(r) => r,
        Err(_) => Err(XisoError::new(
            ERR_UNKNOWN,
            Some("Erro inesperado durante o processamento".into()),
        )),
    };

    // Encerra a thread observadora e emite o resultado final.
    shared.done.store(true, Ordering::Relaxed);

    if let Some(vm) = JAVA_VM.get() {
        if let Ok(mut attached) = vm.attach_current_thread() {
            let env: &mut JNIEnv = &mut attached;
            report_finished(env, &callback, &result);
        }
    }
}

fn null_or<'a>(value: &'a Option<JObject<'a>>, null: &'a JObject<'a>) -> &'a JObject<'a> {
    value.as_ref().unwrap_or(null)
}

fn report_progress(env: &mut JNIEnv, callback: &GlobalRef, processed: u64, total: u64, file: &str) {
    let file_obj: Option<JObject> = if file.is_empty() {
        None
    } else {
        env.new_string(file).ok().map(JObject::from)
    };
    let null = JObject::null();
    let file_arg: &JObject = null_or(&file_obj, &null);

    let _ = env.call_method(
        callback.as_obj(),
        "onProgress",
        "(JJLjava/lang/String;)V",
        &[
            JValue::Long(processed as i64),
            JValue::Long(total as i64),
            JValue::Object(file_arg),
        ],
    );
}

fn report_finished(env: &mut JNIEnv, callback: &GlobalRef, result: &Result<(), XisoError>) {
    let (code, message) = match result {
        Ok(()) => (error::ERR_OK, None),
        Err(e) => (e.code, e.detail.clone()),
    };

    let msg_obj: Option<JObject> = message
        .as_deref()
        .and_then(|m| env.new_string(m).ok())
        .map(JObject::from);
    let null = JObject::null();
    let msg_arg: &JObject = null_or(&msg_obj, &null);

    let _ = env.call_method(
        callback.as_obj(),
        "onFinished",
        "(ILjava/lang/String;)V",
        &[JValue::Int(code as jint), JValue::Object(msg_arg)],
    );
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_xisotool_nativebridge_XisoNative_extractIso<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    iso_path: JString<'local>,
    out_dir: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    start_task(&mut env, iso_path, out_dir, callback, OpKind::Extract)
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_xisotool_nativebridge_XisoNative_createIso<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    src_dir: JString<'local>,
    out_iso: JString<'local>,
    callback: JObject<'local>,
) -> jlong {
    start_task(&mut env, src_dir, out_iso, callback, OpKind::Create)
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_xisotool_nativebridge_XisoNative_cancel(
    _env: JNIEnv,
    _class: JClass,
    op_id: jlong,
) {
    if let Ok(ops) = active_ops().lock() {
        if let Some(flag) = ops.get(&op_id) {
            flag.store(true, Ordering::SeqCst);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_xisotool_nativebridge_XisoNative_checkXiso<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    iso_path: JString<'local>,
) -> jboolean {
    match read_string(&mut env, &iso_path) {
        Some(path) => {
            if ops::is_xiso(Path::new(&path)) {
                1
            } else {
                0
            }
        }
        None => 0,
    }
}
