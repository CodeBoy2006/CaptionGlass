#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <ggml-backend.h>
#include <ggml-vulkan.h>
#include <ggml-opencl.h>
#include <ggml-hexagon.h>
#include <gguf.h>
#include <atomic>
#include <chrono>
#include <climits>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <cstdlib>

using Clock = std::chrono::steady_clock;
static constexpr int batch_size = 8;
struct Call {
    std::atomic<bool> cancelled{false};
    Clock::time_point deadline;
    explicit Call(int64_t ms) : deadline(Clock::now() + std::chrono::milliseconds(ms)) {}
};
static bool aborted(void * data) {
    auto * c = static_cast<Call *>(data);
    return c->cancelled.load(std::memory_order_relaxed) || Clock::now() >= c->deadline;
}
struct Cancelled : std::runtime_error { Cancelled() : std::runtime_error("cancelled_or_deadline") {} };
static void check(Call * c) { if (aborted(c)) throw Cancelled(); }
struct BackendError : std::runtime_error { using std::runtime_error::runtime_error; };
struct ModelUnsupported : std::runtime_error { using std::runtime_error::runtime_error; };
static std::mutex backend_mutex;
struct Model {
    bool usable = true;
    double clear_ms = 0;
    double prefill_ms = 0, prompt_decode_ms = 0, first_token_ms = -1, total_ms = 0;
    int prompt_tokens = 0, history_tokens = 0, cached_tokens = 0, output_tokens = 0;
    std::vector<llama_token> cached_prefix;
    std::string backend;
    ggml_backend_dev_t devices[2]{};
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    ~Model() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
        if (backend == "hexagon" && devices[0]) ggml_backend_hexagon_release_device(devices[0]);
    }
};
static void decode(Model * m, llama_batch batch, Call * call) {
    const auto status = llama_decode(m->ctx, batch);
    llama_synchronize(m->ctx);
    check(call);
    if (status != 0) throw std::runtime_error("decode_failed");
}
struct Batch {
    llama_batch b = llama_batch_init(batch_size, 0, 1);
    ~Batch() { llama_batch_free(b); }
};
static void prefill(Model * m, llama_batch & batch, const std::vector<llama_token> & tokens, int pos, Call * call) {
    while (pos < static_cast<int>(tokens.size())) {
        check(call);
        batch.n_tokens = std::min(batch_size, static_cast<int>(tokens.size()) - pos);
        for (int i = 0; i < batch.n_tokens; ++i) {
            batch.token[i] = tokens[pos + i]; batch.pos[i] = pos + i;
            batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0;
            batch.logits[i] = pos + i == static_cast<int>(tokens.size()) - 1;
        }
        decode(m, batch, call);
        pos += batch.n_tokens;
    }
}
static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & s, bool special);
static std::string bytes(JNIEnv * env, jbyteArray input) {
    std::string s(env->GetArrayLength(input), '\0');
    env->GetByteArrayRegion(input, 0, static_cast<jsize>(s.size()), reinterpret_cast<jbyte *>(s.data()));
    return s;
}
static void fail(JNIEnv * env, const std::exception & e, bool unavailable = false) {
    // A callback exception must not hide the owner's obligation to discard a poisoned context.
    if (unavailable && env->ExceptionCheck()) env->ExceptionClear();
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(unavailable ?
        "com/captionglass/nativebridge/TranslationUnavailableException" :
        dynamic_cast<const BackendError *>(&e) ? "com/captionglass/nativebridge/TranslationBackendException" :
        dynamic_cast<const ModelUnsupported *>(&e) ? "com/captionglass/nativebridge/TranslationModelUnsupportedException" :
        "java/lang/IllegalStateException"), e.what());
}
static jbyteArray output_bytes(JNIEnv * env, const std::string & output) {
    auto result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
    return result;
}
#define JNI(name) Java_com_captionglass_nativebridge_NativeBindings_##name
static std::string timings(Model * m) {
    const auto perf = llama_perf_context(m->ctx);
    return "backend=" + m->backend + " prefill_ms=" + std::to_string(m->prefill_ms) + " decode_ms=" + std::to_string(perf.t_eval_ms - m->prompt_decode_ms) +
        " clear_ms=" + std::to_string(m->clear_ms) + " first_token_ms=" + std::to_string(m->first_token_ms) +
        " total_ms=" + std::to_string(m->total_ms) + " prompt_tokens=" + std::to_string(m->prompt_tokens) +
        " history_tokens=" + std::to_string(m->history_tokens) + " cached_tokens=" + std::to_string(m->cached_tokens) +
        " output_tokens=" + std::to_string(m->output_tokens);
}
static void report(Model * m, Clock::time_point began, const char * result) {
    m->total_ms = std::chrono::duration<double, std::milli>(Clock::now() - began).count();
    // Counts and timings only; never log captured speech, history or translations.
    __android_log_print(ANDROID_LOG_INFO, "CaptionGlassNative", "MT result=%s %s", result, timings(m).c_str());
}
extern "C" JNIEXPORT jlong JNICALL JNI(newCall)(JNIEnv *, jobject, jlong ms) {
    return reinterpret_cast<jlong>(new Call(ms));
}
extern "C" JNIEXPORT void JNICALL JNI(cancel)(JNIEnv *, jobject, jlong c) {
    reinterpret_cast<Call *>(c)->cancelled.store(true, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL JNI(freeCall)(JNIEnv *, jobject, jlong c) { delete reinterpret_cast<Call *>(c); }
extern "C" JNIEXPORT void JNICALL JNI(unload)(JNIEnv *, jobject, jlong m) { delete reinterpret_cast<Model *>(m); }
extern "C" JNIEXPORT jstring JNICALL JNI(timings)(JNIEnv * env, jobject, jlong model) {
    return env->NewStringUTF(timings(reinterpret_cast<Model *>(model)).c_str()); // ASCII-only diagnostics.
}
extern "C" JNIEXPORT jboolean JNICALL JNI(hexagonAvailable)(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(backend_mutex);
    try {
        auto reg = ggml_backend_hexagon_reg();
        return reg && ggml_backend_reg_dev_count(reg) > 0;
    } catch (const std::exception &) { return false; }
}

static std::string string(JNIEnv * env, jstring input) {
    const char * chars = env->GetStringUTFChars(input, nullptr);
    if (!chars) throw std::runtime_error("string_allocation_failed");
    std::string result(chars);
    env->ReleaseStringUTFChars(input, chars);
    return result;
}

static void check_hexagon_model(const std::string & path) {
    // Read tensor metadata only. K-quants otherwise silently put the model's matmuls on CPU.
    auto file = std::unique_ptr<gguf_context, decltype(&gguf_free)>(
        gguf_init_from_file(path.c_str(), {true, nullptr}), gguf_free);
    if (!file) throw std::runtime_error("model_metadata_failed");
    for (int64_t i = 0; i < gguf_get_n_tensors(file.get()); ++i) {
        // Matches the pinned Hexagon MUL_MAT types; re-audit when upgrading the runtime.
        switch (gguf_get_tensor_type(file.get(), i)) {
            case GGML_TYPE_F32: case GGML_TYPE_F16: case GGML_TYPE_Q4_0: case GGML_TYPE_Q4_1:
            case GGML_TYPE_Q8_0: case GGML_TYPE_IQ4_NL: case GGML_TYPE_MXFP4: break;
            default: throw ModelUnsupported("hexagon_model_quantization_unsupported");
        }
    }
}

extern "C" JNIEXPORT jlong JNICALL JNI(load)(JNIEnv * env, jobject, jbyteArray path, jbyteArray prefix, jlong token,
                                               jstring backend, jstring runtime_directory) {
    try {
        auto * call = reinterpret_cast<Call *>(token);
        check(call);
        static std::once_flag once;
        std::call_once(once, [] {
            llama_log_set([](ggml_log_level level, const char * text, void *) {
                if (level == GGML_LOG_LEVEL_INFO || level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN)
                    __android_log_print(level == GGML_LOG_LEVEL_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
                                        "CaptionGlassNative", "%s", text);
            }, nullptr);
            llama_backend_init();
        });
        auto m = std::make_unique<Model>();
        m->backend = string(env, backend);
        const bool cpu = m->backend == "cpu", hexagon = m->backend == "hexagon", opencl = m->backend == "opencl";
        if (!cpu && !hexagon && !opencl && m->backend != "vulkan") throw BackendError("unknown_backend");
        const auto model_path = bytes(env, path);
        if (hexagon) check_hexagon_model(model_path);
        {
            std::lock_guard<std::mutex> lock(backend_mutex);
            if (!cpu) {
                ggml_backend_reg_t reg = nullptr;
                try {
                    if (hexagon) {
                        auto directory = string(env, runtime_directory);
                        if (directory.empty() || setenv("ADSP_LIBRARY_PATH", directory.c_str(), 1) != 0)
                            throw BackendError("hexagon_runtime_unavailable");
                        reg = ggml_backend_hexagon_reg();
                    } else if (opencl) {
                        __android_log_print(ANDROID_LOG_INFO, "CaptionGlassNative", "MT discovering OpenCL device");
                        auto directory = string(env, runtime_directory);
                        if (directory.empty() || setenv("GGML_OPENCL_KERNEL_CACHE_DIR", directory.c_str(), 1) != 0)
                            throw BackendError("opencl_runtime_unavailable");
                        if (!getenv("GGML_DISABLE_OPENCL")) reg = ggml_backend_opencl_reg();
                    } else if (!getenv("GGML_DISABLE_VULKAN")) reg = ggml_backend_vk_reg();
                    if (reg) ggml_backend_register(reg);
                    if (!reg || ggml_backend_reg_dev_count(reg) == 0) throw BackendError("device_unavailable");
                    m->devices[0] = ggml_backend_reg_dev_get(reg, 0);
                    // Open the real DSP session before llama can swallow an initialization error.
                    if (hexagon) {
                        auto probe = ggml_backend_dev_init(m->devices[0], nullptr);
                        if (!probe) throw BackendError("hexagon_session_unavailable");
                        ggml_backend_free(probe);
                    }
                } catch (const std::exception & e) {
                    __android_log_print(ANDROID_LOG_WARN, "CaptionGlassNative", "MT backend=%s unavailable: %s", m->backend.c_str(), e.what());
                    throw BackendError(m->backend + "_device_unavailable");
                }
            }
        }
        __android_log_print(ANDROID_LOG_INFO, "CaptionGlassNative", "MT backend=%s device=%s", m->backend.c_str(),
                            cpu ? "CPU" : ggml_backend_dev_name(m->devices[0]));
        auto mp = llama_model_default_params();
        // A non-null, empty device list prevents automatic accelerator selection in CPU mode.
        mp.devices = m->devices;
        mp.n_gpu_layers = cpu ? 0 : INT_MAX;
        mp.split_mode = LLAMA_SPLIT_MODE_NONE;
        mp.load_mode = hexagon ? LLAMA_LOAD_MODE_NONE : LLAMA_LOAD_MODE_MMAP;
        mp.progress_callback = [](float, void * c) { return !aborted(c); };
        mp.progress_callback_user_data = call;
        m->model = llama_model_load_from_file(model_path.c_str(), mp);
        if (!m->model) throw std::runtime_error("model_load_failed");
        check(call);
        auto cp = llama_context_default_params();
        // Small batches bound cancellation latency and fit short subtitle prompts.
        // ponytail: poll between accelerator batches; in-flight device work cannot be preempted.
        cp.n_ctx = 2048; cp.n_batch = batch_size; cp.n_ubatch = batch_size;
        cp.n_threads = 3; cp.n_threads_batch = 3;
        cp.offload_kqv = !cpu; cp.op_offload = !cpu;
        cp.no_perf = false;
        cp.abort_callback = aborted; cp.abort_callback_data = call;
        m->ctx = llama_init_from_model(m->model, cp);
        if (!m->ctx) throw std::runtime_error("context_load_failed");
        // Charge fixed instructions to model preparation, not the first live segment's deadline.
        auto fixed = tokenize(llama_model_get_vocab(m->model), bytes(env, prefix), true);
        if (fixed.size() >= cp.n_ctx) throw std::runtime_error("prefix_budget");
        Batch batch;
        prefill(m.get(), batch.b, fixed, 0, call);
        m->cached_prefix = std::move(fixed);
        llama_set_abort_callback(m->ctx, nullptr, nullptr);
        check(call);
        return reinterpret_cast<jlong>(m.release());
    } catch (const std::exception & e) { fail(env, e); return 0; }
}
static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & s, bool special) {
    int n = llama_tokenize(vocab, s.data(), static_cast<int>(s.size()), nullptr, 0, false, special);
    std::vector<llama_token> tokens(n < 0 ? -n : n);
    n = llama_tokenize(vocab, s.data(), static_cast<int>(s.size()), tokens.data(), static_cast<int>(tokens.size()), false, special);
    if (n < 0) throw std::runtime_error("tokenization_failed");
    tokens.resize(n);
    return tokens;
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI(translate)(JNIEnv * env, jobject, jlong model,
                                                       jbyteArray prefix, jbyteArray input, jbyteArray ending, jbyteArray background, jint sampling, jlong token, jint limit, jobject progress) {
    auto * m = reinterpret_cast<Model *>(model);
    auto * call = reinterpret_cast<Call *>(token);
    const auto began = Clock::now();
    auto prefill_start = Clock::time_point{};
    m->clear_ms = m->prefill_ms = m->prompt_decode_ms = m->total_ms = 0;
    m->first_token_ms = -1;
    m->prompt_tokens = m->history_tokens = m->cached_tokens = m->output_tokens = 0;
    llama_perf_context_reset(m->ctx);
    if (!m->usable) {
        report(m, began, "context_unavailable");
        fail(env, std::runtime_error("translation_context_failed_restart_session"), true);
        return nullptr;
    }
    try {
        check(call);
        jmethodID callback = nullptr;
        if (progress) {
            auto type = env->GetObjectClass(progress);
            callback = env->GetMethodID(type, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
            env->DeleteLocalRef(type);
            if (!callback) throw std::runtime_error("progress_callback_failed");
        }
        llama_set_abort_callback(m->ctx, aborted, call);
        const auto * vocab = llama_model_get_vocab(m->model);
        // Only APK-owned template pieces parse role tokens. Speech/history remain literal.
        auto tokens = tokenize(vocab, bytes(env, prefix), true);
        auto prefix_tokens = tokens;
        auto history = tokenize(vocab, bytes(env, background), false);
        // Optional context is either complete or omitted, never an arbitrary suffix of a sentence.
        if (!history.empty() && history.size() <= 64) {
            auto header = tokenize(vocab, "[Background Information]\n", false);
            auto separator = tokenize(vocab, "\n\n", false);
            tokens.insert(tokens.end(), header.begin(), header.end());
            tokens.insert(tokens.end(), history.begin(), history.end());
            tokens.insert(tokens.end(), separator.begin(), separator.end());
            m->history_tokens = static_cast<int>(history.size());
        }
        auto content = tokenize(vocab, bytes(env, input), false);
        auto suffix = tokenize(vocab, bytes(env, ending), true);
        tokens.insert(tokens.end(), content.begin(), content.end());
        tokens.insert(tokens.end(), suffix.begin(), suffix.end());
        if (tokens.empty() || limit < 1 || limit > 256 || tokens.size() + limit > 2048) throw std::runtime_error("context_budget");
        m->prompt_tokens = static_cast<int>(tokens.size());
        // Retain only an identical APK-owned prefix. Speech, history and generated tokens are removed.
        // Leave at least one prompt token to decode: retained KV does not restore the final logits.
        prefix_tokens.resize(std::min(prefix_tokens.size(), tokens.size() - 1));
        if (m->cached_prefix == prefix_tokens) m->cached_tokens = static_cast<int>(prefix_tokens.size());
        else {
            if (!m->cached_prefix.empty()) llama_memory_clear(llama_get_memory(m->ctx), true);
            m->cached_prefix.clear();
        }
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (sampling == 1 || sampling == 2) {
            if (sampling == 2) {
                // A stray closing think token can resume analysis after a complete visible answer.
                // This adapter emits translations only; suppress reasoning instead of hiding its cost.
                const auto begin = tokenize(vocab, "<think>", true), end = tokenize(vocab, "</think>", true);
                if (begin.size() != 1 || end.size() != 1) throw std::runtime_error("reasoning_tokens_unavailable");
                const float disabled = -std::numeric_limits<float>::infinity();
                const llama_logit_bias bias[] = {{begin[0], disabled}, {end[0], disabled}};
                llama_sampler_chain_add(sampler.get(), llama_sampler_init_logit_bias(llama_vocab_n_tokens(vocab), 2, bias));
            }
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        } else if (sampling == 0) {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.f, 0.f));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.6f, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
        } else throw std::runtime_error("unknown_sampling");
        Batch storage;
        auto & batch = storage.b;
        prefill_start = Clock::now();
        prefill(m, batch, tokens, m->cached_tokens, call);
        int pos = static_cast<int>(tokens.size());
        m->prefill_ms = std::chrono::duration<double, std::milli>(Clock::now() - prefill_start).count();
        // Upstream counts a final one-token prompt batch as decode; exclude it from generation time.
        m->prompt_decode_ms = llama_perf_context(m->ctx).t_eval_ms;
        std::string output;
        bool complete = false;
        auto last_progress = Clock::time_point{};
        for (int i = 0; i < limit; ++i) {
            check(call);
            const auto t = llama_sampler_sample(sampler.get(), m->ctx, -1);
            if (llama_vocab_is_eog(vocab, t)) { complete = true; break; }
            if (m->output_tokens++ == 0) m->first_token_ms = std::chrono::duration<double, std::milli>(Clock::now() - began).count();
            char piece[256];
            int n = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, false);
            if (n < 0) {
                std::vector<char> large(-n);
                n = llama_token_to_piece(vocab, t, large.data(), static_cast<int>(large.size()), 0, false);
                if (n < 0) throw std::runtime_error("detokenization_failed");
                output.append(large.data(), n);
            } else output.append(piece, n);
            if (output.size() > 32768) throw std::runtime_error("output_budget");
            if (callback && Clock::now() - last_progress >= std::chrono::milliseconds(120)) {
                auto value = output_bytes(env, output);
                if (!value) throw std::runtime_error("progress_allocation_failed");
                auto unit = env->CallObjectMethod(progress, callback, value);
                env->DeleteLocalRef(value);
                if (unit) env->DeleteLocalRef(unit);
                if (env->ExceptionCheck()) throw std::runtime_error("progress_callback_failed");
                last_progress = Clock::now();
            }
            batch.n_tokens = 1; batch.token[0] = t; batch.pos[0] = pos++;
            batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = true;
            decode(m, batch, call);
        }
        check(call);
        if (!complete || output.empty()) throw std::runtime_error("output_budget_or_empty");
        auto result = output_bytes(env, output);
        if (!result) throw std::runtime_error("output_allocation_failed");
        llama_set_abort_callback(m->ctx, nullptr, nullptr);
        const auto clear_start = Clock::now();
        if (!llama_memory_seq_rm(llama_get_memory(m->ctx), 0, static_cast<int>(prefix_tokens.size()), -1))
            throw std::runtime_error("prefix_cache_cleanup_failed");
        m->cached_prefix = std::move(prefix_tokens);
        m->clear_ms = std::chrono::duration<double, std::milli>(Clock::now() - clear_start).count();
        report(m, began, "complete");
        return result;
    } catch (const std::exception & e) {
        if (prefill_start != Clock::time_point{} && m->prefill_ms == 0) {
            m->prefill_ms = std::chrono::duration<double, std::milli>(Clock::now() - prefill_start).count();
            m->prompt_decode_ms = llama_perf_context(m->ctx).t_eval_ms;
        }
        // Retain the call token and context until all submitted work has returned.
        const bool cancelled = dynamic_cast<const Cancelled *>(&e) != nullptr;
        if (m->backend == "hexagon" && ggml_backend_hexagon_device_failed(m->devices[0])) m->usable = false;
        if (!cancelled) m->cached_prefix.clear();
        llama_set_abort_callback(m->ctx, nullptr, nullptr);
        const auto clear_start = Clock::now();
        try {
            llama_synchronize(m->ctx);
            // A normal cancellation touched only the request's suffix; keep the prepared fixed prefix.
            if (cancelled && !m->cached_prefix.empty()) {
                if (!llama_memory_seq_rm(llama_get_memory(m->ctx), 0, static_cast<int>(m->cached_prefix.size()), -1))
                    throw std::runtime_error("prefix_cache_cleanup_failed");
            } else llama_memory_clear(llama_get_memory(m->ctx), true);
        } catch (const std::exception & cleanup) {
            m->usable = false;
            m->cached_prefix.clear();
            __android_log_print(ANDROID_LOG_ERROR, "CaptionGlassNative", "MT cleanup failed: %s", cleanup.what());
        }
        m->clear_ms = std::chrono::duration<double, std::milli>(Clock::now() - clear_start).count();
        report(m, began, !m->usable ? "context_unavailable" : aborted(call) ?
            (call->cancelled.load() ? "cancelled" : "deadline") : e.what());
        fail(env, e, !m->usable); return nullptr;
    }
}
