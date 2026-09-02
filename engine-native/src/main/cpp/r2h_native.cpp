/**
 * r2h_native.cpp — JNI bridge between the Kotlin engine-native module and
 * the llama.cpp inference runtime.
 *
 * Threading contract (enforced by engine-core, not here):
 *   - generate() is called on Dispatchers.IO, never on the main thread.
 *   - cancel() may be called from any thread; uses a per-context atomic flag.
 *   - createContext / destroyContext are serialized by engine-core; never called
 *     concurrently with generate() on the same handle.
 *
 * Memory model:
 *   - createContext allocates an R2hContext on the heap and returns its address
 *     as a jlong handle. The caller (InferenceOrchestrator) owns the handle and
 *     must call destroyContext exactly once when done.
 *   - All JNI local references are explicitly deleted; no leaks in normal paths.
 *   - llama_batch objects are allocated per-operation and freed before return.
 */

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "common.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "ncnn/net.h"
#include "whisper.h"

#define LOG_TAG "r2h_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Error codes: must match NativeErrorCode.kt ───────────────────────────────
static constexpr jint ERR_SUCCESS          = 0;
static constexpr jint ERR_INVALID_HANDLE   = 1;
static constexpr jint ERR_INVALID_PARAMS   = 2;
static constexpr jint ERR_INFERENCE_FAILED = 3;
static constexpr jint ERR_CANCELLED        = 4;

// ─── Finish codes: must match NativeFinishCode.kt ────────────────────────────
static constexpr jint FINISH_COMPLETE   = 0;
static constexpr jint FINISH_MAX_TOKENS = 1;
static constexpr jint FINISH_CANCELLED  = 2;
static constexpr jint FINISH_ERROR      = 3;

// ─── Per-context state ────────────────────────────────────────────────────────
struct R2hContext {
    llama_model*      model = nullptr;
    llama_context*    ctx   = nullptr;
    std::atomic<bool> cancel{false};
};

struct R2hMtmdContext {
    llama_model*      model = nullptr;
    llama_context*    ctx   = nullptr;
    mtmd_context*     mtmd  = nullptr;
    common_chat_templates_ptr tmpls;
    std::atomic<bool> cancel{false};
    int32_t           n_batch = 512;
};

// ─── llama.cpp backend initialisation ────────────────────────────────────────
static std::once_flag g_backend_init_flag;

static void ensure_backend_init() {
    std::call_once(g_backend_init_flag, []() {
        llama_backend_init();
        LOGI("llama_backend_init complete");
    });
}

static void r2h_clear_memory(llama_context* ctx) {
    if (!ctx) return;
    llama_memory_t memory = llama_get_memory(ctx);
    if (memory) {
        llama_memory_clear(memory, true);
    }
}

// ─── JNI class/method ID cache ───────────────────────────────────────────────
static jclass    g_result_class   = nullptr;
static jmethodID g_result_ctor    = nullptr;  // (IIII)V
static jclass    g_metadata_class = nullptr;
static jmethodID g_metadata_ctor  = nullptr;  // (JIILjava/lang/String;)V

static bool ensure_result_class(JNIEnv* env) {
    if (g_result_class) return true;
    jclass local = env->FindClass("io/r2h/engine/nativebridge/NativeGenerateResult");
    if (!local) {
        LOGE("FindClass NativeGenerateResult failed");
        return false;
    }
    g_result_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    g_result_ctor = env->GetMethodID(g_result_class, "<init>", "(IIII)V");
    if (!g_result_ctor) {
        LOGE("GetMethodID NativeGenerateResult.<init>(IIII)V failed");
        return false;
    }
    return true;
}

static bool ensure_metadata_class(JNIEnv* env) {
    if (g_metadata_class) return true;
    jclass local = env->FindClass("io/r2h/engine/nativebridge/NativeModelMetadata");
    if (!local) {
        LOGE("FindClass NativeModelMetadata failed");
        return false;
    }
    g_metadata_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    g_metadata_ctor = env->GetMethodID(
        g_metadata_class, "<init>", "(JIILjava/lang/String;)V");
    if (!g_metadata_ctor) {
        LOGE("GetMethodID NativeModelMetadata ctor failed");
        return false;
    }
    return true;
}

static jobject make_result(JNIEnv* env, jint err, jint prompt_toks, jint gen_toks, jint finish) {
    if (!ensure_result_class(env)) return nullptr;
    return env->NewObject(g_result_class, g_result_ctor, err, prompt_toks, gen_toks, finish);
}

static std::string json_escape(const std::string& raw) {
    std::string out;
    out.reserve(raw.size());
    for (char c : raw) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"':  out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:   out += c; break;
        }
    }
    return out;
}

static jstring make_probe_json(
        JNIEnv* env,
        const char* status,
        const char* backend,
        const std::string& output,
        const char* error_code,
        const std::string& error_message) {
    std::ostringstream os;
    os << "{"
       << "\"status\":\"" << status << "\","
       << "\"backendLabel\":\"" << backend << "\","
       << "\"outputPreview\":\"" << json_escape(output) << "\","
       << "\"errorCode\":";
    if (error_code) {
        os << "\"" << error_code << "\"";
    } else {
        os << "null";
    }
    os << ",\"errorMessage\":";
    if (!error_message.empty()) {
        os << "\"" << json_escape(error_message) << "\"";
    } else {
        os << "null";
    }
    os << "}";
    return env->NewStringUTF(os.str().c_str());
}

static std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// ─── Batch helpers ────────────────────────────────────────────────────────────
static inline void batch_add_token(
        llama_batch& batch,
        llama_token  token,
        llama_pos    pos,
        bool         compute_logits) {
    batch.token[batch.n_tokens]     = token;
    batch.pos[batch.n_tokens]       = pos;
    batch.n_seq_id[batch.n_tokens]  = 1;
    batch.seq_id[batch.n_tokens][0] = 0;
    batch.logits[batch.n_tokens]    = compute_logits ? 1 : 0;
    ++batch.n_tokens;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_r2h_engine_nativebridge_NativeInferenceEngine_createContext(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path,
        jint    max_context_length,
        jint    threads) {

    ensure_backend_init();

    const char* path_cstr = env->GetStringUTFChars(j_model_path, nullptr);
    if (!path_cstr) {
        LOGE("createContext: GetStringUTFChars returned null");
        return 0L;
    }

    const std::string model_path(path_cstr);
    env->ReleaseStringUTFChars(j_model_path, path_cstr);

    LOGI("createContext: path_len=%zu ctx=%d threads=%d",
         model_path.size(), max_context_length, threads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model* model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) {
        LOGE("createContext: llama_model_load_from_file failed");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(max_context_length);
    cparams.n_threads       = static_cast<int32_t>(threads);
    cparams.n_threads_batch = static_cast<int32_t>(threads);

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("createContext: llama_init_from_model failed");
        llama_model_free(model);
        return 0L;
    }

    auto* r2h = new R2hContext();
    r2h->model = model;
    r2h->ctx   = ctx;

    LOGI("createContext: success handle=%p n_ctx=%d",
         static_cast<void*>(r2h), llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(r2h);
}

JNIEXPORT jlong JNICALL
Java_io_r2h_engine_nativebridge_NativeInferenceEngine_createEmbeddingContext(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path,
        jint    max_context_length,
        jint    threads) {

    ensure_backend_init();

    const char* path_cstr = env->GetStringUTFChars(j_model_path, nullptr);
    if (!path_cstr) {
        LOGE("createEmbeddingContext: GetStringUTFChars returned null");
        return 0L;
    }

    const std::string model_path(path_cstr);
    env->ReleaseStringUTFChars(j_model_path, path_cstr);

    LOGI("createEmbeddingContext: path_len=%zu ctx=%d threads=%d",
         model_path.size(), max_context_length, threads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model* model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) {
        LOGE("createEmbeddingContext: llama_model_load_from_file failed");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(max_context_length);
    cparams.n_threads       = static_cast<int32_t>(threads);
    cparams.n_threads_batch = static_cast<int32_t>(threads);
    cparams.embeddings      = true;
    cparams.pooling_type    = LLAMA_POOLING_TYPE_LAST;
    cparams.attention_type  = LLAMA_ATTENTION_TYPE_CAUSAL;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("createEmbeddingContext: llama_init_from_model failed");
        llama_model_free(model);
        return 0L;
    }

    llama_set_embeddings(ctx, true);

    auto* r2h = new R2hContext();
    r2h->model = model;
    r2h->ctx   = ctx;

    LOGI("createEmbeddingContext: success handle=%p n_ctx=%d n_embd_out=%d",
         static_cast<void*>(r2h), llama_n_ctx(ctx), llama_model_n_embd_out(model));
    return reinterpret_cast<jlong>(r2h);
}

JNIEXPORT void JNICALL
Java_io_r2h_engine_nativebridge_NativeInferenceEngine_destroyContext(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   handle) {

    if (!handle) return;

    auto* r2h = reinterpret_cast<R2hContext*>(handle);
    LOGI("destroyContext: handle=%p", static_cast<void*>(r2h));

    if (r2h->ctx) {
        llama_free(r2h->ctx);
    }
    if (r2h->model) {
        llama_model_free(r2h->model);
    }
    delete r2h;
}

JNIEXPORT jobject JNICALL
Java_io_r2h_engine_nativebridge_NativeInferenceEngine_generate(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   handle,
        jstring j_prompt,
        jint    max_tokens,
        jfloat  temperature,
        jobject token_callback) {

    if (!handle) {
        LOGE("generate: called with null handle");
        return make_result(env, ERR_INVALID_HANDLE, -1, 0, FINISH_ERROR);
    }

    auto* r2h = reinterpret_cast<R2hContext*>(handle);
    if (!r2h->model || !r2h->ctx) {
        LOGE("generate: invalid context internals");
        return make_result(env, ERR_INVALID_HANDLE, -1, 0, FINISH_ERROR);
    }

    r2h->cancel.store(false, std::memory_order_release);

    const char* prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    if (!prompt_cstr) {
        LOGE("generate: GetStringUTFChars(prompt) failed");
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }

    const std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    if (prompt.empty()) {
        LOGE("generate: empty prompt");
        return make_result(env, ERR_INVALID_PARAMS, 0, 0, FINISH_ERROR);
    }

    LOGI("generate: handle=%p prompt_len=%zu max_tokens=%d temp=%.2f",
         static_cast<void*>(r2h), prompt.size(), max_tokens, temperature);

    jclass cb_class = env->GetObjectClass(token_callback);
    if (!cb_class) {
        LOGE("generate: token_callback class is null");
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }

    jmethodID on_tok = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cb_class);
    if (!on_tok) {
        LOGE("generate: could not find TokenCallback.onToken(String)");
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }

    const int n_ctx = static_cast<int>(llama_n_ctx(r2h->ctx));
    const llama_vocab* vocab = llama_model_get_vocab(r2h->model);
    if (!vocab) {
        LOGE("generate: llama_model_get_vocab returned null");
        return make_result(env, ERR_INFERENCE_FAILED, -1, 0, FINISH_ERROR);
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_ctx));
    const int n_prompt = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(),
        static_cast<int32_t>(prompt_tokens.size()),
        true,
        true
    );

    if (n_prompt < 0) {
        LOGE("generate: llama_tokenize failed (returned %d)", n_prompt);
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }
    if (n_prompt >= n_ctx) {
        LOGE("generate: prompt too long: %d tokens >= ctx %d", n_prompt, n_ctx);
        return make_result(env, ERR_INVALID_PARAMS, n_prompt, 0, FINISH_ERROR);
    }

    prompt_tokens.resize(static_cast<size_t>(n_prompt));
    LOGI("generate: tokenised prompt to %d tokens (n_ctx=%d)", n_prompt, n_ctx);

    llama_batch batch = llama_batch_init(n_prompt, 0, 1);
    for (int i = 0; i < n_prompt; ++i) {
        batch_add_token(batch, prompt_tokens[static_cast<size_t>(i)], static_cast<llama_pos>(i), i == n_prompt - 1);
    }

    const int prefill_ret = llama_decode(r2h->ctx, batch);
    llama_batch_free(batch);

    if (prefill_ret < 0) {
        LOGE("generate: llama_decode (prefill) error: %d", prefill_ret);
        r2h_clear_memory(r2h->ctx);
        return make_result(env, ERR_INFERENCE_FAILED, n_prompt, 0, FINISH_ERROR);
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    int n_gen = 0;
    int n_cur = n_prompt;
    int finish = FINISH_COMPLETE;

    char piece_buf[256];

    while (n_gen < max_tokens) {
        if (r2h->cancel.load(std::memory_order_acquire)) {
            LOGI("generate: cancelled by caller after %d tokens", n_gen);
            finish = FINISH_CANCELLED;
            break;
        }

        const llama_token token_id = llama_sampler_sample(smpl, r2h->ctx, -1);
        llama_sampler_accept(smpl, token_id);

        if (llama_vocab_is_eog(vocab, token_id)) {
            LOGI("generate: EOG token after %d generated tokens", n_gen);
            finish = FINISH_COMPLETE;
            break;
        }

        const int32_t piece_len = llama_token_to_piece(
            vocab,
            token_id,
            piece_buf,
            static_cast<int32_t>(sizeof(piece_buf) - 1),
            0,
            false
        );

        if (piece_len > 0) {
            piece_buf[piece_len] = '\0';

            jstring j_piece = env->NewStringUTF(piece_buf);
            if (j_piece) {
                env->CallVoidMethod(token_callback, on_tok, j_piece);
                env->DeleteLocalRef(j_piece);

                if (env->ExceptionCheck()) {
                    LOGW("generate: exception in onToken callback at token %d; stopping generation", n_gen);
                    env->ExceptionClear();
                    finish = FINISH_CANCELLED;
                    break;
                }
            }
        }

        ++n_gen;
        ++n_cur;

        if (n_cur >= n_ctx) {
            LOGI("generate: context full at %d tokens (n_cur=%d)", n_gen, n_cur);
            finish = FINISH_MAX_TOKENS;
            break;
        }

        llama_batch next_batch = llama_batch_init(1, 0, 1);
        batch_add_token(next_batch, token_id, static_cast<llama_pos>(n_cur - 1), true);

        const int decode_ret = llama_decode(r2h->ctx, next_batch);
        llama_batch_free(next_batch);

        if (decode_ret < 0) {
            LOGE("generate: llama_decode error at token %d: %d", n_gen, decode_ret);
            finish = FINISH_ERROR;
            break;
        }
    }

    if (n_gen >= max_tokens && finish == FINISH_COMPLETE) {
        finish = FINISH_MAX_TOKENS;
    }

    llama_sampler_free(smpl);
    r2h_clear_memory(r2h->ctx);

    LOGI("generate: done — n_prompt=%d n_gen=%d finish=%d", n_prompt, n_gen, finish);

    const jint err_code =
        (finish == FINISH_ERROR)     ? ERR_INFERENCE_FAILED :
        (finish == FINISH_CANCELLED) ? ERR_CANCELLED :
                                       ERR_SUCCESS;

    return make_result(env, err_code, n_prompt, n_gen, finish);
}

JNIEXPORT jfloatArray JNICALL
Java_io_r2h_engine_nativebridge_NativeInferenceEngine_embedText(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   handle,
        jstring j_text) {

    if (!handle) {
        LOGE("embedText: called with null handle");
        return nullptr;
    }

    auto* r2h = reinterpret_cast<R2hContext*>(handle);
    if (!r2h->model || !r2h->ctx) {
        LOGE("embedText: invalid context internals");
        return nullptr;
    }

    const char* text_cstr = env->GetStringUTFChars(j_text, nullptr);
    if (!text_cstr) {
        LOGE("embedText: GetStringUTFChars(text) failed");
        return nullptr;
    }

    const std::string text(text_cstr);
    env->ReleaseStringUTFChars(j_text, text_cstr);

    if (text.empty()) {
        LOGE("embedText: empty text");
        return nullptr;
    }

    const int n_ctx = static_cast<int>(llama_n_ctx(r2h->ctx));
    const llama_vocab* vocab = llama_model_get_vocab(r2h->model);
    if (!vocab) {
        LOGE("embedText: llama_model_get_vocab returned null");
        return nullptr;
    }

    std::vector<llama_token> tokens(static_cast<size_t>(n_ctx));
    int n_tokens = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );

    if (n_tokens < 0) {
        LOGE("embedText: llama_tokenize failed (returned %d)", n_tokens);
        return nullptr;
    }

    tokens.resize(static_cast<size_t>(n_tokens));
    const llama_token eos = llama_vocab_eos(vocab);
    if (eos >= 0 && (tokens.empty() || tokens.back() != eos)) {
        tokens.push_back(eos);
    }

    if (tokens.empty() || static_cast<int>(tokens.size()) >= n_ctx) {
        LOGE("embedText: token count invalid: %zu n_ctx=%d", tokens.size(), n_ctx);
        return nullptr;
    }

    r2h_clear_memory(r2h->ctx);
    llama_set_embeddings(r2h->ctx, true);

    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    for (int i = 0; i < static_cast<int>(tokens.size()); ++i) {
        batch_add_token(batch, tokens[static_cast<size_t>(i)], static_cast<llama_pos>(i), true);
    }

    const int decode_ret = llama_decode(r2h->ctx, batch);
    llama_batch_free(batch);

    if (decode_ret < 0) {
        LOGE("embedText: llama_decode error: %d", decode_ret);
        r2h_clear_memory(r2h->ctx);
        return nullptr;
    }

    const int n_embd = static_cast<int>(llama_model_n_embd_out(r2h->model));
    float* emb = llama_get_embeddings_seq(r2h->ctx, 0);
    if (!emb) {
        emb = llama_get_embeddings_ith(r2h->ctx, -1);
    }
    if (!emb || n_embd <= 0) {
        LOGE("embedText: embedding pointer unavailable n_embd=%d", n_embd);
        r2h_clear_memory(r2h->ctx);
        return nullptr;
    }

    jfloatArray out = env->NewFloatArray(n_embd);
    if (!out) {
        LOGE("embedText: NewFloatArray failed");
        r2h_clear_memory(r2h->ctx);
        return nullptr;
    }

    env->SetFloatArrayRegion(out, 0, n_embd, emb);
    r2h_clear_memory(r2h->ctx);

    LOGI("embedText: produced vector dimension=%d tokens=%zu", n_embd, tokens.size());
    return out;
}

JNIEXPORT jlong JNICALL
Java_io_r2h_engine_nativebridge_R2hMtmdNativeBridge_loadMultimodalModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path,
        jstring j_mmproj_path,
        jint    max_context_length,
        jint    threads) {

    ensure_backend_init();

    const std::string model_path = jstring_to_string(env, j_model_path);
    const std::string mmproj_path = jstring_to_string(env, j_mmproj_path);
    if (model_path.empty() || mmproj_path.empty() || max_context_length <= 0 || threads <= 0) {
        LOGE("mtmd load: invalid params model_path_len=%zu mmproj_path_len=%zu ctx=%d threads=%d",
             model_path.size(), mmproj_path.size(), max_context_length, threads);
        return 0L;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model* model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) {
        LOGE("mtmd load: llama_model_load_from_file failed");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(max_context_length);
    cparams.n_threads       = static_cast<int32_t>(threads);
    cparams.n_threads_batch = static_cast<int32_t>(threads);

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("mtmd load: llama_init_from_model failed");
        llama_model_free(model);
        return 0L;
    }

    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = false;
    mtmd_params.n_threads = static_cast<int32_t>(threads);
    mtmd_params.batch_max_tokens = 512;
    mtmd_context* mtmd = mtmd_init_from_file(mmproj_path.c_str(), model, mtmd_params);
    if (!mtmd) {
        LOGE("mtmd load: mtmd_init_from_file failed");
        llama_free(ctx);
        llama_model_free(model);
        return 0L;
    }
    if (!mtmd_support_vision(mtmd)) {
        LOGE("mtmd load: projector does not report vision support");
        mtmd_free(mtmd);
        llama_free(ctx);
        llama_model_free(model);
        return 0L;
    }

    common_chat_templates_ptr tmpls = common_chat_templates_init(model, "");
    if (!tmpls) {
        LOGE("mtmd load: common_chat_templates_init failed");
        mtmd_free(mtmd);
        llama_free(ctx);
        llama_model_free(model);
        return 0L;
    }

    auto* r2h = new R2hMtmdContext();
    r2h->model = model;
    r2h->ctx = ctx;
    r2h->mtmd = mtmd;
    r2h->tmpls = std::move(tmpls);
    r2h->n_batch = 512;
    LOGI("mtmd load: success handle=%p model_path_len=%zu mmproj_path_len=%zu n_ctx=%d",
         static_cast<void*>(r2h), model_path.size(), mmproj_path.size(), llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(r2h);
}

JNIEXPORT jobject JNICALL
Java_io_r2h_engine_nativebridge_R2hMtmdNativeBridge_generateFromImage(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   handle,
        jstring j_prompt,
        jstring j_image_path,
        jint    max_tokens,
        jfloat  temperature,
        jobject token_callback) {

    if (!handle) {
        LOGE("mtmd generateFromImage: called with null handle");
        return make_result(env, ERR_INVALID_HANDLE, -1, 0, FINISH_ERROR);
    }
    auto* r2h = reinterpret_cast<R2hMtmdContext*>(handle);
    if (!r2h->model || !r2h->ctx || !r2h->mtmd) {
        LOGE("mtmd generateFromImage: invalid context internals");
        return make_result(env, ERR_INVALID_HANDLE, -1, 0, FINISH_ERROR);
    }
    if (max_tokens <= 0 || !token_callback) {
        LOGE("mtmd generateFromImage: invalid max_tokens/callback");
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }

    const std::string prompt = jstring_to_string(env, j_prompt);
    const std::string image_path = jstring_to_string(env, j_image_path);
    if (prompt.empty() || image_path.empty()) {
        LOGE("mtmd generateFromImage: empty prompt or image path");
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }

    jclass cb_class = env->GetObjectClass(token_callback);
    if (!cb_class) {
        LOGE("mtmd generateFromImage: token_callback class is null");
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }
    jmethodID on_tok = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cb_class);
    if (!on_tok) {
        LOGE("mtmd generateFromImage: could not find TokenCallback.onToken(String)");
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }

    r2h->cancel.store(false, std::memory_order_release);
    r2h_clear_memory(r2h->ctx);

    const char* marker = mtmd_default_marker();
    std::string user_prompt = prompt;
    if (user_prompt.find(marker) == std::string::npos) {
        user_prompt = std::string(marker) + prompt;
    }

    common_chat_msg msg;
    msg.role = "user";
    msg.content = user_prompt;
    std::vector<common_chat_msg> empty_history;
    std::string formatted_prompt = common_chat_format_single(
        r2h->tmpls.get(),
        empty_history,
        msg,
        true,
        true);

    LOGI("mtmd generateFromImage: prompt_len=%zu image_path_len=%zu max_tokens=%d temp=%.2f",
         formatted_prompt.size(), image_path.size(), max_tokens, temperature);

    mtmd_helper_bitmap_wrapper media = mtmd_helper_bitmap_init_from_file(r2h->mtmd, image_path.c_str(), false);
    if (!media.bitmap) {
        LOGE("mtmd generateFromImage: image load failed");
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    mtmd_input_text text{formatted_prompt.c_str(), true, true};
    const mtmd_bitmap* bitmaps[1] = {media.bitmap};
    const int32_t tok_ret = mtmd_tokenize(r2h->mtmd, chunks, &text, bitmaps, 1);
    if (tok_ret != 0 || !chunks) {
        LOGE("mtmd generateFromImage: mtmd_tokenize failed ret=%d", tok_ret);
        if (chunks) mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(media.bitmap);
        if (media.video_ctx) mtmd_helper_video_free(media.video_ctx);
        return make_result(env, ERR_INVALID_PARAMS, -1, 0, FINISH_ERROR);
    }

    const size_t n_prompt_tokens = mtmd_helper_get_n_tokens(chunks);
    llama_pos n_past = 0;
    const int32_t eval_ret = mtmd_helper_eval_chunks(
        r2h->mtmd,
        r2h->ctx,
        chunks,
        0,
        0,
        r2h->n_batch,
        true,
        &n_past);

    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(media.bitmap);
    if (media.video_ctx) mtmd_helper_video_free(media.video_ctx);

    if (eval_ret != 0) {
        LOGE("mtmd generateFromImage: mtmd_helper_eval_chunks failed ret=%d", eval_ret);
        r2h_clear_memory(r2h->ctx);
        return make_result(env, ERR_INFERENCE_FAILED, static_cast<jint>(n_prompt_tokens), 0, FINISH_ERROR);
    }

    const llama_vocab* vocab = llama_model_get_vocab(r2h->model);
    if (!vocab) {
        LOGE("mtmd generateFromImage: llama_model_get_vocab returned null");
        r2h_clear_memory(r2h->ctx);
        return make_result(env, ERR_INFERENCE_FAILED, static_cast<jint>(n_prompt_tokens), 0, FINISH_ERROR);
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    int n_gen = 0;
    int finish = FINISH_COMPLETE;
    const int n_ctx = static_cast<int>(llama_n_ctx(r2h->ctx));
    llama_tokens generated_tokens;
    generated_tokens.reserve(static_cast<size_t>(max_tokens));

    while (n_gen < max_tokens) {
        if (r2h->cancel.load(std::memory_order_acquire)) {
            finish = FINISH_CANCELLED;
            break;
        }

        const llama_token token_id = llama_sampler_sample(smpl, r2h->ctx, -1);
        llama_sampler_accept(smpl, token_id);
        generated_tokens.push_back(token_id);
        if (llama_vocab_is_eog(vocab, token_id)) {
            finish = FINISH_COMPLETE;
            break;
        }

        const std::string piece = common_token_to_piece(r2h->ctx, token_id);
        if (!piece.empty()) {
            jstring j_piece = env->NewStringUTF(piece.c_str());
            if (j_piece) {
                env->CallVoidMethod(token_callback, on_tok, j_piece);
                env->DeleteLocalRef(j_piece);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                finish = FINISH_CANCELLED;
                break;
            }
        }

        ++n_gen;
        ++n_past;
        if (n_past >= n_ctx) {
            finish = FINISH_MAX_TOKENS;
            break;
        }

        llama_batch next_batch = llama_batch_init(1, 0, 1);
        batch_add_token(next_batch, token_id, n_past - 1, true);
        const int decode_ret = llama_decode(r2h->ctx, next_batch);
        llama_batch_free(next_batch);
        if (decode_ret < 0) {
            LOGE("mtmd generateFromImage: llama_decode failed token=%d ret=%d", n_gen, decode_ret);
            finish = FINISH_ERROR;
            break;
        }
    }

    if (n_gen >= max_tokens && finish == FINISH_COMPLETE) {
        finish = FINISH_MAX_TOKENS;
    }

    const std::string generated_text = common_detokenize(r2h->ctx, generated_tokens);
    LOGI("mtmd generateFromImage: detokenized_text_len=%zu", generated_text.size());

    llama_sampler_free(smpl);
    r2h_clear_memory(r2h->ctx);

    LOGI("mtmd generateFromImage: done prompt_tokens=%zu generated=%d finish=%d",
         n_prompt_tokens, n_gen, finish);

    const jint err_code =
        (finish == FINISH_ERROR)     ? ERR_INFERENCE_FAILED :
        (finish == FINISH_CANCELLED) ? ERR_CANCELLED :
                                       ERR_SUCCESS;

    return make_result(env, err_code, static_cast<jint>(n_prompt_tokens), n_gen, finish);
}

JNIEXPORT void JNICALL
Java_io_r2h_engine_nativebridge_R2hMtmdNativeBridge_unloadMultimodalModel(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (!handle) return;
    auto* r2h = reinterpret_cast<R2hMtmdContext*>(handle);
    LOGI("mtmd unload: handle=%p", static_cast<void*>(r2h));
    if (r2h->mtmd) mtmd_free(r2h->mtmd);
    if (r2h->ctx) llama_free(r2h->ctx);
    if (r2h->model) llama_model_free(r2h->model);
    delete r2h;
}

JNIEXPORT jstring JNICALL
Java_io_r2h_engine_nativebridge_NativeInferenceEngine_getBackendLabel(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   handle) {

    if (!handle) {
        return env->NewStringUTF("llama.cpp:unloaded");
    }
    auto* r2h = reinterpret_cast<R2hContext*>(handle);
    if (!r2h->ctx || !r2h->model) {
        return env->NewStringUTF("llama.cpp:invalid");
    }
    return env->NewStringUTF("llama.cpp");
}

JNIEXPORT void JNICALL
Java_io_r2h_engine_nativebridge_NativeInferenceEngine_cancel(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   handle) {

    if (!handle) return;
    auto* r2h = reinterpret_cast<R2hContext*>(handle);
    r2h->cancel.store(true, std::memory_order_release);
    LOGI("cancel: handle=%p", static_cast<void*>(r2h));
}

JNIEXPORT jobject JNICALL
Java_io_r2h_engine_nativebridge_NativeInferenceEngine_readModelMetadata(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path) {

    ensure_backend_init();

    if (!ensure_metadata_class(env)) return nullptr;

    const char* path_cstr = env->GetStringUTFChars(j_model_path, nullptr);
    if (!path_cstr) return nullptr;

    const std::string model_path(path_cstr);
    env->ReleaseStringUTFChars(j_model_path, path_cstr);

    LOGI("readModelMetadata: path_len=%zu", model_path.size());

    llama_model_params mparams = llama_model_default_params();
    mparams.vocab_only = true;

    llama_model* model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) {
        LOGE("readModelMetadata: failed to load metadata");
        return nullptr;
    }

    const jint context_length   = static_cast<jint>(llama_model_n_ctx_train(model));
    const jint embedding_length = static_cast<jint>(llama_model_n_embd(model));

    jlong param_count = 0;
    char meta_buf[128];

    if (llama_model_meta_val_str(model, "general.parameter_count", meta_buf, sizeof(meta_buf)) > 0) {
        try {
            param_count = static_cast<jlong>(std::stoll(meta_buf));
        } catch (...) {
        }
    }

    const char* quant_cstr = "UNKNOWN";
    if (llama_model_meta_val_str(model, "general.quantization_version", meta_buf, sizeof(meta_buf)) > 0) {
        quant_cstr = meta_buf;
    }

    jstring j_quant = env->NewStringUTF(quant_cstr);

    jobject result = env->NewObject(
        g_metadata_class,
        g_metadata_ctor,
        param_count,
        context_length,
        embedding_length,
        j_quant
    );

    env->DeleteLocalRef(j_quant);
    llama_model_free(model);

    LOGI("readModelMetadata: ctx=%d embd=%d params=%lld",
         context_length, embedding_length, static_cast<long long>(param_count));

    return result;
}

JNIEXPORT jstring JNICALL
Java_io_r2h_engine_nativebridge_NativeRuntimeSmokeBridge_yoloNcnnSmoke(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_param_path,
        jstring j_bin_path) {
    const std::string param_path = jstring_to_string(env, j_param_path);
    const std::string bin_path = jstring_to_string(env, j_bin_path);
    if (param_path.empty() || bin_path.empty()) {
        return make_probe_json(env, "MODEL_MISSING", "NCNN", "", "INVALID_PATH", "NCNN param/bin path is empty");
    }

    try {
        ncnn::Net net;
        net.opt.num_threads = 2;
        net.opt.use_vulkan_compute = false;

        const int param_ret = net.load_param(param_path.c_str());
        if (param_ret != 0) {
            return make_probe_json(env, "SMOKE_FAILED", "NCNN", "", "LOAD_PARAM_FAILED", "net.load_param returned " + std::to_string(param_ret));
        }
        const int model_ret = net.load_model(bin_path.c_str());
        if (model_ret != 0) {
            return make_probe_json(env, "SMOKE_FAILED", "NCNN", "", "LOAD_MODEL_FAILED", "net.load_model returned " + std::to_string(model_ret));
        }

        const auto& inputs = net.input_names();
        const auto& outputs = net.output_names();
        if (inputs.empty() || outputs.empty()) {
            return make_probe_json(env, "SMOKE_FAILED", "NCNN", "", "NO_IO_NAMES", "NCNN graph did not expose input/output names");
        }

        ncnn::Mat image(640, 640, 3);
        image.fill(0.0f);
        ncnn::Extractor extractor = net.create_extractor();
        const int input_ret = extractor.input(inputs[0], image);
        if (input_ret != 0) {
            return make_probe_json(env, "SMOKE_FAILED", "NCNN", "", "INPUT_FAILED", "extractor.input returned " + std::to_string(input_ret));
        }

        ncnn::Mat output;
        const int extract_ret = extractor.extract(outputs[0], output);
        if (extract_ret != 0) {
            return make_probe_json(env, "SMOKE_FAILED", "NCNN", "", "EXTRACT_FAILED", "extractor.extract returned " + std::to_string(extract_ret));
        }
        if (output.total() == 0) {
            return make_probe_json(env, "SMOKE_FAILED", "NCNN", "", "EMPTY_OUTPUT", "NCNN output tensor is empty");
        }

        std::ostringstream preview;
        preview << "input=" << inputs[0]
                << " output=" << outputs[0]
                << " dims=" << output.dims
                << " w=" << output.w
                << " h=" << output.h
                << " c=" << output.c
                << " total=" << output.total();
        return make_probe_json(env, "AVAILABLE", "NCNN", preview.str(), nullptr, "");
    } catch (const std::exception& e) {
        return make_probe_json(env, "SMOKE_FAILED", "NCNN", "", "EXCEPTION", e.what());
    }
}

JNIEXPORT jstring JNICALL
Java_io_r2h_engine_nativebridge_NativeRuntimeSmokeBridge_whisperCppSmoke(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path,
        jfloatArray j_pcm_f32,
        jint sample_rate) {
    const std::string model_path = jstring_to_string(env, j_model_path);
    if (model_path.empty() || !j_pcm_f32 || sample_rate != 16000) {
        return make_probe_json(env, "MODEL_MISSING", "whisper.cpp", "", "INVALID_INPUT", "Whisper requires a model path and 16 kHz PCM");
    }

    jsize n_samples = env->GetArrayLength(j_pcm_f32);
    if (n_samples <= 0) {
        return make_probe_json(env, "SMOKE_FAILED", "whisper.cpp", "", "EMPTY_AUDIO", "PCM buffer is empty");
    }
    std::vector<float> pcm(static_cast<size_t>(n_samples));
    env->GetFloatArrayRegion(j_pcm_f32, 0, n_samples, pcm.data());

    whisper_context_params cparams = whisper_context_default_params();
    whisper_context* ctx = whisper_init_from_file_with_params(model_path.c_str(), cparams);
    if (!ctx) {
        return make_probe_json(env, "SMOKE_FAILED", "whisper.cpp", "", "LOAD_MODEL_FAILED", model_path);
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = 2;

    const int ret = whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size()));
    if (ret != 0) {
        whisper_free(ctx);
        return make_probe_json(env, "SMOKE_FAILED", "whisper.cpp", "", "TRANSCRIBE_FAILED", "whisper_full returned " + std::to_string(ret));
    }

    std::string transcript;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text) transcript += text;
    }
    whisper_free(ctx);

    if (transcript.empty()) {
        return make_probe_json(env, "SMOKE_FAILED", "whisper.cpp", "", "EMPTY_TRANSCRIPT", "whisper.cpp returned no transcript text");
    }
    return make_probe_json(env, "AVAILABLE", "whisper.cpp", transcript.substr(0, 160), nullptr, "");
}

JNIEXPORT jstring JNICALL
Java_io_r2h_engine_nativebridge_NativeRuntimeSmokeBridge_qwenImageMtmdSmoke(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path,
        jstring j_mmproj_path,
        jbyteArray j_rgb,
        jint width,
        jint height) {
    ensure_backend_init();

    const std::string model_path = jstring_to_string(env, j_model_path);
    const std::string mmproj_path = jstring_to_string(env, j_mmproj_path);
    if (model_path.empty() || mmproj_path.empty() || !j_rgb || width <= 0 || height <= 0) {
        return make_probe_json(env, "MODEL_MISSING", "llama.cpp mtmd", "", "INVALID_INPUT", "Qwen image smoke requires model, mmproj, and RGB image");
    }

    const jsize expected = width * height * 3;
    if (env->GetArrayLength(j_rgb) != expected) {
        return make_probe_json(env, "SMOKE_FAILED", "llama.cpp mtmd", "", "INVALID_RGB_SIZE", "RGB byte count does not match width*height*3");
    }
    std::vector<unsigned char> rgb(static_cast<size_t>(expected));
    env->GetByteArrayRegion(j_rgb, 0, expected, reinterpret_cast<jbyte*>(rgb.data()));

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model* model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) {
        return make_probe_json(env, "SMOKE_FAILED", "llama.cpp mtmd", "", "TEXT_MODEL_LOAD_FAILED", model_path);
    }

    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = false;
    mtmd_params.n_threads = 2;
    mtmd_context* mtmd = mtmd_init_from_file(mmproj_path.c_str(), model, mtmd_params);
    if (!mtmd) {
        llama_model_free(model);
        return make_probe_json(env, "SMOKE_FAILED", "llama.cpp mtmd", "", "MMPROJ_LOAD_FAILED", mmproj_path);
    }
    if (!mtmd_support_vision(mtmd)) {
        mtmd_free(mtmd);
        llama_model_free(model);
        return make_probe_json(env, "RUNTIME_MISSING", "llama.cpp mtmd", "", "VISION_NOT_SUPPORTED", "mtmd loaded but does not report vision support");
    }

    mtmd_bitmap* bitmap = mtmd_bitmap_init(static_cast<uint32_t>(width), static_cast<uint32_t>(height), rgb.data());
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    const std::string prompt = std::string("Describe this image: ") + mtmd_default_marker();
    mtmd_input_text text{prompt.c_str(), true, true};
    const mtmd_bitmap* bitmaps[1] = {bitmap};
    const int32_t tok_ret = mtmd_tokenize(mtmd, chunks, &text, bitmaps, 1);
    const size_t chunk_count = chunks ? mtmd_input_chunks_size(chunks) : 0;

    if (chunks) mtmd_input_chunks_free(chunks);
    if (bitmap) mtmd_bitmap_free(bitmap);
    mtmd_free(mtmd);
    llama_model_free(model);

    if (tok_ret != 0 || chunk_count == 0) {
        return make_probe_json(env, "SMOKE_FAILED", "llama.cpp mtmd", "", "IMAGE_TOKENIZE_FAILED", "mtmd_tokenize returned " + std::to_string(tok_ret));
    }

    std::ostringstream preview;
    preview << "vision=true width=" << width << " height=" << height << " chunks=" << chunk_count;
    return make_probe_json(env, "AVAILABLE", "llama.cpp mtmd", preview.str(), nullptr, "");
}

} // extern "C"
