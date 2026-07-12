// Thin JNI bridge over upstream llama.cpp (pinned tag b5199) for the Task 1 spike.
//
// Design rules:
// - No inference logic reimplemented here: tokenize/prefill/decode map 1:1 onto
//   llama.cpp calls; policy (threads, context, cancellation) lives in Kotlin.
// - Token pieces cross JNI as raw UTF-8 bytes, never jstring: BPE pieces may split
//   multi-byte code points, and NewStringUTF on invalid UTF-8 aborts under CheckJNI.
//   Kotlin owns streaming UTF-8 reassembly.
// - Cancellation is a per-context atomic flag polled every token (and every prefill
//   chunk), giving <= 1 token cancellation latency (ARCHITECTURE.md §12).

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <vector>

#include "llama.h"

#define LOG_TAG "TouvayLlamaSpike"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct SpikeContext {
    llama_context *ctx = nullptr;
    const llama_model *model = nullptr;
    std::atomic_bool cancel{false};
};

llama_model *as_model(jlong handle) { return reinterpret_cast<llama_model *>(handle); }
SpikeContext *as_context(jlong handle) { return reinterpret_cast<SpikeContext *>(handle); }

constexpr int32_t kPrefillChunk = 512;

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeBackendInit(JNIEnv *, jobject) {
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeLoadModel(
        JNIEnv *env, jobject, jstring path, jboolean use_mmap) {
    const char *c_path = env->GetStringUTFChars(path, nullptr);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;  // CPU-only spike baseline
    params.use_mmap = use_mmap;
    llama_model *model = llama_model_load_from_file(c_path, params);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed for %s", c_path);
    }
    env->ReleaseStringUTFChars(path, c_path);
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jint JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeModelCtxTrain(
        JNIEnv *, jobject, jlong model) {
    return llama_model_n_ctx_train(as_model(model));
}

JNIEXPORT jlong JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeCreateContext(
        JNIEnv *, jobject, jlong model, jint n_ctx, jint n_threads, jint n_batch) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(n_ctx);
    params.n_batch = static_cast<uint32_t>(n_batch);
    params.n_ubatch = static_cast<uint32_t>(n_batch);
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    params.no_perf = true;
    llama_context *ctx = llama_init_from_model(as_model(model), params);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed (n_ctx=%d, n_threads=%d)", n_ctx, n_threads);
        return 0;
    }
    auto *wrapper = new SpikeContext();
    wrapper->ctx = ctx;
    wrapper->model = as_model(model);
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jintArray JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeTokenize(
        JNIEnv *env, jobject, jlong model, jstring text, jboolean add_special) {
    const char *c_text = env->GetStringUTFChars(text, nullptr);
    const auto text_len = static_cast<int32_t>(strlen(c_text));
    const llama_vocab *vocab = llama_model_get_vocab(as_model(model));

    std::vector<llama_token> tokens(text_len + 16);
    int32_t count = llama_tokenize(vocab, c_text, text_len, tokens.data(),
                                   static_cast<int32_t>(tokens.size()),
                                   add_special, /*parse_special=*/true);
    if (count < 0) {
        tokens.resize(-count);
        count = llama_tokenize(vocab, c_text, text_len, tokens.data(),
                               static_cast<int32_t>(tokens.size()),
                               add_special, /*parse_special=*/true);
    }
    env->ReleaseStringUTFChars(text, c_text);
    if (count < 0) {
        LOGE("llama_tokenize failed");
        return nullptr;
    }

    static_assert(sizeof(llama_token) == sizeof(jint), "llama_token must be 32-bit");
    jintArray out = env->NewIntArray(count);
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, count, reinterpret_cast<jint *>(tokens.data()));
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativePrefill(
        JNIEnv *env, jobject, jlong ctx_handle, jintArray tokens) {
    SpikeContext *sc = as_context(ctx_handle);
    sc->cancel.store(false);

    const jsize count = env->GetArrayLength(tokens);
    std::vector<llama_token> toks(static_cast<size_t>(count));
    env->GetIntArrayRegion(tokens, 0, count, reinterpret_cast<jint *>(toks.data()));

    for (jsize offset = 0; offset < count; offset += kPrefillChunk) {
        if (sc->cancel.load(std::memory_order_relaxed)) {
            return -2;  // cancelled
        }
        const auto len = std::min<int32_t>(kPrefillChunk, count - offset);
        llama_batch batch = llama_batch_get_one(toks.data() + offset, len);
        const int32_t status = llama_decode(sc->ctx, batch);
        if (status != 0) {
            LOGE("llama_decode (prefill) failed: %d", status);
            return status;
        }
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeDecode(
        JNIEnv *env, jobject, jlong ctx_handle, jint max_tokens, jobject callback) {
    SpikeContext *sc = as_context(ctx_handle);
    const llama_vocab *vocab = llama_model_get_vocab(sc->model);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_class, "onToken", "(I[B)Z");
    if (on_token == nullptr) {
        return -3;
    }

    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    llama_sampler *sampler = llama_sampler_chain_init(chain_params);
    // Greedy decoding only in the spike: deterministic, and sampling strategy is a
    // capability-pipeline concern, not a runtime concern.
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    int produced = 0;
    while (produced < max_tokens && !sc->cancel.load(std::memory_order_relaxed)) {
        const llama_token token = llama_sampler_sample(sampler, sc->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        char piece[256];
        const int32_t piece_len =
                llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        const int32_t safe_len = piece_len > 0 ? piece_len : 0;
        jbyteArray bytes = env->NewByteArray(safe_len);
        if (bytes == nullptr) {
            break;  // JVM OOM on a tiny array: give up cleanly
        }
        if (safe_len > 0) {
            env->SetByteArrayRegion(bytes, 0, safe_len,
                                    reinterpret_cast<const jbyte *>(piece));
        }
        const jboolean keep_going = env->CallBooleanMethod(callback, on_token, token, bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            break;
        }
        produced++;
        if (!keep_going) {
            break;
        }

        llama_token next = token;
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(sc->ctx, batch) != 0) {
            LOGE("llama_decode (step) failed");
            break;
        }
    }

    llama_sampler_free(sampler);
    return produced;
}

JNIEXPORT void JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeCancel(
        JNIEnv *, jobject, jlong ctx_handle) {
    as_context(ctx_handle)->cancel.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeFreeContext(
        JNIEnv *, jobject, jlong ctx_handle) {
    SpikeContext *sc = as_context(ctx_handle);
    if (sc == nullptr) return;
    llama_free(sc->ctx);
    delete sc;
}

JNIEXPORT void JNICALL
Java_com_touvay_runtime_llamacpp_spike_LlamaNative_nativeFreeModel(
        JNIEnv *, jobject, jlong model) {
    llama_model_free(as_model(model));
}

}  // extern "C"
