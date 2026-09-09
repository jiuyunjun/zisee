//! Call-owned, synchronous JNI bridge. No JNI arrays, tensors or PCM buffers allocated per frame.
use df::tract::{DfParams, DfTract, RuntimeParams};
use jni::{objects::{JByteArray, JByteBuffer, JClass}, sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE}, JNIEnv};
use ndarray::Array2;
use std::panic::{catch_unwind, AssertUnwindSafe};

struct Processor {
    model: DfTract,
    input: Array2<f32>,
    output: Array2<f32>,
    gain: f32,
    failed: bool,
}

#[no_mangle]
pub extern "system" fn Java_com_zisee_app_rtc_audio_processing_DeepFilterNative_create(
    env: JNIEnv, _: JClass, bytes: JByteArray,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(bytes).ok()?;
        let params = DfParams::from_bytes(&bytes).ok()?;
        let runtime = RuntimeParams::default_with_ch(1).with_atten_lim(30.0);
        let model = DfTract::new(params, &runtime).ok()?;
        if model.hop_size != 480 { return None; }
        Some(Box::into_raw(Box::new(Processor {
            model, input: Array2::zeros((1, 480)), output: Array2::zeros((1, 480)), gain: 1.0, failed: false,
        })) as jlong)
    })).ok().flatten().unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_zisee_app_rtc_audio_processing_DeepFilterNative_process(
    env: JNIEnv, _: JClass, pointer: jlong, buffer: JByteBuffer,
) -> jboolean {
    if pointer == 0 { return JNI_FALSE; }
    // Kotlin owns the pointer and serializes all access, including release.
    let state = unsafe { &mut *(pointer as *mut Processor) };
    if state.failed { return JNI_FALSE; }
    let result = catch_unwind(AssertUnwindSafe(|| {
        if env.get_direct_buffer_capacity(&buffer).ok()? != 480 * 4 { return None; }
        let address = env.get_direct_buffer_address(&buffer).ok()?;
        if (address as usize) % std::mem::align_of::<f32>() != 0 { return None; }
        let samples = unsafe { std::slice::from_raw_parts_mut(address as *mut f32, 480) };
        for (dest, &sample) in state.input.iter_mut().zip(samples.iter()) {
            if !sample.is_finite() { return None; }
            *dest = (sample / 32768.0).clamp(-1.0, 1.0);
        }
        state.model.process(state.input.view(), state.output.view_mut()).ok()?;
        if state.output.iter().any(|s| !s.is_finite()) { return None; }
        // Gentle digital speech gain after NS; don't amplify silence/noise, cap at +12 dB.
        let rms = (state.output.iter().map(|s| s * s).sum::<f32>() / 480.0).sqrt();
        let target = if rms > 0.01 { (0.1 / rms).clamp(0.25, 4.0) } else { 1.0 };
        let smoothing = if target < state.gain { 0.2 } else { 0.005 };
        state.gain += smoothing * (target - state.gain);
        for (dest, sample) in samples.iter_mut().zip(state.output.iter()) {
            *dest = (sample * state.gain).clamp(-0.98, 0.98) * 32768.0;
        }
        Some(())
    }));
    if matches!(result, Ok(Some(()))) { JNI_TRUE } else { state.failed = true; JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_zisee_app_rtc_audio_processing_DeepFilterNative_release(
    _: JNIEnv, _: JClass, pointer: jlong,
) {
    if pointer != 0 { unsafe { drop(Box::from_raw(pointer as *mut Processor)); } }
}
