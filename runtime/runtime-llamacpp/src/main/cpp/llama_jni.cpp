// Thin JNI bridge over upstream llama.cpp (pinned tag b5199) — production adapter.
// Design: docs/runtime/runtime-llamacpp-design.md §3. Deltas vs the Task 1 spike:
//   1. ggml abort callback registered per context: cancellation interrupts INSIDE a
//      decode step (SPI-CX-4), not just between tokens.
//   2. Prefill chunk size is a parameter (documented constant lives in Kotlin).
//   3. Sink exceptions propagate (SPI-ST-6): on a pending Java exception the loop
//      stops and returns WITHOUT ExceptionClear, so the JVM rethrows in Kotlin.
//   4. Decode/prefill return-status mapping distinguishes abort (2) from failure.
//
// Rules: no inference logic here; token pieces cross as raw UTF-8 bytes, never
// NewStringUTF (BPE pieces split code points; CheckJNI aborts on invalid UTF-8).

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "TouvayLlamaCpp"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// llama_decode return code for "aborted via abort_callback" (see llama.h).
constexpr int32_t kDecodeAborted = 2;

struct SessionCtx {
    llama_context *ctx = nullptr;
    const llama_model *model = nullptr;
    std::atomic_bool cancel{false};
};

llama_model *as_model(jlong handle) { return reinterpret_cast<llama_model *>(handle); }
SessionCtx *as_session(jlong handle) { return reinterpret_cast<SessionCtx *>(handle); }

bool abort_requested(void *data) {
    return static_cast<SessionCtx *>(data)->cancel.load(std::memory_order_relaxed);
}

void append_utf8(std::string &out, uint32_t code_point) {
    if (code_point <= 0x7f) {
        out.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
        out.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
        out.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
        out.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    }
}

// JNI's GetStringUTFChars returns modified UTF-8 (CESU-8 for supplementary
// characters and C0 80 for NUL). llama.cpp and Android file paths require standard
// UTF-8, so convert from UTF-16 explicitly at every Java String boundary.
bool jstring_to_utf8(JNIEnv *env, jstring value, std::string &out) {
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return false;  // pending JVM exception

    out.clear();
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t code_point = chars[i];
        if (code_point >= 0xd800 && code_point <= 0xdbff) {
            if (i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
                code_point = 0x10000 + ((code_point - 0xd800) << 10) +
                             (chars[++i] - 0xdc00);
            } else {
                code_point = 0xfffd;
            }
        } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
            code_point = 0xfffd;
        }
        append_utf8(out, code_point);
    }
    env->ReleaseStringChars(value, chars);
    return true;
}

bool token_to_piece(const llama_vocab *vocab, llama_token token, std::vector<char> &piece) {
    piece.resize(256);
    int32_t length = llama_token_to_piece(
            vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
    if (length < 0) {
        piece.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(
                vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
    }
    if (length < 0) return false;
    piece.resize(static_cast<size_t>(length));
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeBackendInit(JNIEnv *, jobject) {
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeLoadModel(
        JNIEnv *env, jobject, jstring path, jboolean use_mmap) {
    std::string utf8_path;
    if (!jstring_to_utf8(env, path, utf8_path)) return 0;
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;   // CPU-only v1 (design §4)
    params.use_mmap = use_mmap;
    params.use_mlock = false;  // SPI-MM-3
    llama_model *model = llama_model_load_from_file(utf8_path.c_str(), params);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file rejected %s", utf8_path.c_str());
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jint JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeModelCtxTrain(
        JNIEnv *, jobject, jlong model) {
    return llama_model_n_ctx_train(as_model(model));
}

JNIEXPORT jlong JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeCreateContext(
        JNIEnv *, jobject, jlong model, jint n_ctx, jint n_threads, jint n_batch) {
    auto *session = new SessionCtx();
    session->model = as_model(model);

    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(n_ctx);
    params.n_batch = static_cast<uint32_t>(n_batch);
    params.n_ubatch = static_cast<uint32_t>(n_batch);
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    params.no_perf = true;
    // SPI-CX-4: interrupt inside a decode step, not only between tokens.
    params.abort_callback = abort_requested;
    params.abort_callback_data = session;

    session->ctx = llama_init_from_model(as_model(model), params);
    if (session->ctx == nullptr) {
        LOGE("llama_init_from_model failed (n_ctx=%d, n_threads=%d)", n_ctx, n_threads);
        delete session;
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jintArray JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeTokenize(
        JNIEnv *env, jobject, jlong model, jstring text, jboolean add_special) {
    std::string utf8_text;
    if (!jstring_to_utf8(env, text, utf8_text)) return nullptr;
    const auto text_len = static_cast<int32_t>(utf8_text.size());
    const llama_vocab *vocab = llama_model_get_vocab(as_model(model));

    std::vector<llama_token> tokens(text_len + 16);
    int32_t count = llama_tokenize(vocab, utf8_text.data(), text_len, tokens.data(),
                                   static_cast<int32_t>(tokens.size()),
                                   add_special, /*parse_special=*/true);
    if (count < 0) {
        tokens.resize(-count);
        count = llama_tokenize(vocab, utf8_text.data(), text_len, tokens.data(),
                               static_cast<int32_t>(tokens.size()),
                               add_special, /*parse_special=*/true);
    }
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

JNIEXPORT jbyteArray JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeDetokenize(
        JNIEnv *env, jobject, jlong model, jintArray token_ids) {
    const llama_vocab *vocab = llama_model_get_vocab(as_model(model));
    const jsize count = env->GetArrayLength(token_ids);
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    env->GetIntArrayRegion(token_ids, 0, count, reinterpret_cast<jint *>(tokens.data()));
    if (env->ExceptionCheck()) return nullptr;

    std::vector<jbyte> all_bytes;
    std::vector<char> piece;
    for (const llama_token token : tokens) {
        if (!token_to_piece(vocab, token, piece)) {
            LOGE("llama_token_to_piece failed");
            return nullptr;
        }
        all_bytes.insert(all_bytes.end(), piece.begin(), piece.end());
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(all_bytes.size()));
    if (out != nullptr && !all_bytes.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(all_bytes.size()), all_bytes.data());
    }
    return out;
}

// Returns 0 = complete; -2 = cancelled cooperatively; other = llama_decode status.
JNIEXPORT jint JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativePrefill(
        JNIEnv *env, jobject, jlong session_handle, jintArray tokens, jint chunk) {
    SessionCtx *session = as_session(session_handle);

    const jsize count = env->GetArrayLength(tokens);
    std::vector<llama_token> toks(static_cast<size_t>(count));
    env->GetIntArrayRegion(tokens, 0, count, reinterpret_cast<jint *>(toks.data()));

    for (jsize offset = 0; offset < count; offset += chunk) {
        if (session->cancel.load(std::memory_order_relaxed)) {
            return -2;
        }
        const auto len = std::min<int32_t>(chunk, count - offset);
        llama_batch batch = llama_batch_get_one(toks.data() + offset, len);
        const int32_t status = llama_decode(session->ctx, batch);
        if (status == kDecodeAborted) {
            return -2;  // abort callback fired mid-chunk
        }
        if (status != 0) {
            LOGE("llama_decode (prefill) failed: %d", status);
            return status;
        }
    }
    return 0;
}

// Returns tokens produced (>= 0); generation may end by EOG, maxTokens, cancellation,
// or a pending Java exception from the sink (which then propagates on return).
// Returns a negative value only for backend decode failure.
JNIEXPORT jint JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeDecode(
        JNIEnv *env, jobject, jlong session_handle, jint max_tokens, jobject callback) {
    SessionCtx *session = as_session(session_handle);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_class, "onToken", "(I[B)Z");
    if (on_token == nullptr) {
        return -3;
    }

    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    llama_sampler *sampler = llama_sampler_chain_init(chain_params);
    // Greedy only: deterministic (TCK-DT); sampling strategy is a pipeline concern.
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    int produced = 0;
    int failure = 0;
    std::vector<char> piece;
    while (produced < max_tokens && !session->cancel.load(std::memory_order_relaxed)) {
        const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;  // SPI-ST-4: EOG never reaches the sink
        }

        if (!token_to_piece(vocab, token, piece)) {
            LOGE("llama_token_to_piece failed");
            failure = 4;
            break;
        }
        const auto piece_len = static_cast<jsize>(piece.size());
        jbyteArray bytes = env->NewByteArray(piece_len);
        if (bytes == nullptr) {
            break;  // pending OutOfMemoryError propagates on return
        }
        if (piece_len > 0) {
            env->SetByteArrayRegion(bytes, 0, piece_len,
                                    reinterpret_cast<const jbyte *>(piece.data()));
        }
        const jboolean keep_going = env->CallBooleanMethod(callback, on_token, token, bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) {
            break;  // SPI-ST-6: do NOT clear — the sink's exception propagates
        }
        produced++;
        if (!keep_going) {
            break;
        }

        llama_token next = token;
        llama_batch batch = llama_batch_get_one(&next, 1);
        const int32_t status = llama_decode(session->ctx, batch);
        if (status == kDecodeAborted) {
            break;  // cancellation observed inside the step
        }
        if (status != 0) {
            LOGE("llama_decode (step) failed: %d", status);
            failure = status;
            break;
        }
    }

    llama_sampler_free(sampler);
    return failure != 0 ? -1000 - failure : produced;
}

JNIEXPORT void JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeCancel(
        JNIEnv *, jobject, jlong session_handle) {
    as_session(session_handle)->cancel.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeFreeContext(
        JNIEnv *, jobject, jlong session_handle) {
    SessionCtx *session = as_session(session_handle);
    if (session == nullptr) return;
    llama_free(session->ctx);
    delete session;
}

JNIEXPORT void JNICALL
Java_com_touvay_runtime_llamacpp_LlamaNative_nativeFreeModel(
        JNIEnv *, jobject, jlong model) {
    llama_model_free(as_model(model));
}

}  // extern "C"
