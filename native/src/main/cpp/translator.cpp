#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <ggml-backend.h>
#include <atomic>
#include <chrono>
#include <climits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

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
static void check(Call * c) { if (aborted(c)) throw std::runtime_error("cancelled_or_deadline"); }
struct Model {
    bool usable = true;
    double clear_ms = 0;
    ggml_backend_dev_t devices[2]{};
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    ~Model() { if (ctx) llama_free(ctx); if (model) llama_model_free(model); }
};
static void decode(Model * m, llama_batch batch, Call * call) {
    const auto status = llama_decode(m->ctx, batch);
    llama_synchronize(m->ctx);
    check(call);
    if (status != 0) throw std::runtime_error("decode_failed");
}
static std::string bytes(JNIEnv * env, jbyteArray input) {
    std::string s(env->GetArrayLength(input), '\0');
    env->GetByteArrayRegion(input, 0, static_cast<jsize>(s.size()), reinterpret_cast<jbyte *>(s.data()));
    return s;
}
static void fail(JNIEnv * env, const std::exception & e) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
static jbyteArray output_bytes(JNIEnv * env, const std::string & output) {
    auto result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
    return result;
}
#define JNI(name) Java_com_captionglass_nativebridge_NativeBindings_##name
extern "C" JNIEXPORT jlong JNICALL JNI(newCall)(JNIEnv *, jobject, jlong ms) {
    return reinterpret_cast<jlong>(new Call(ms));
}
extern "C" JNIEXPORT void JNICALL JNI(cancel)(JNIEnv *, jobject, jlong c) {
    reinterpret_cast<Call *>(c)->cancelled.store(true, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL JNI(freeCall)(JNIEnv *, jobject, jlong c) { delete reinterpret_cast<Call *>(c); }
extern "C" JNIEXPORT void JNICALL JNI(unload)(JNIEnv *, jobject, jlong m) { delete reinterpret_cast<Model *>(m); }
extern "C" JNIEXPORT jstring JNICALL JNI(timings)(JNIEnv * env, jobject, jlong model) {
    auto * m = reinterpret_cast<Model *>(model);
    const auto perf = llama_perf_context(m->ctx);
    const auto text = "prefill_ms=" + std::to_string(perf.t_p_eval_ms) + " decode_ms=" +
        std::to_string(perf.t_eval_ms) + " clear_ms=" + std::to_string(m->clear_ms);
    return env->NewStringUTF(text.c_str()); // ASCII-only diagnostic numbers.
}
extern "C" JNIEXPORT jlong JNICALL JNI(load)(JNIEnv * env, jobject, jbyteArray path, jlong token) {
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
        auto vulkan = ggml_backend_reg_by_name("Vulkan");
        if (!vulkan || ggml_backend_reg_dev_count(vulkan) == 0)
            throw std::runtime_error("vulkan_device_unavailable");
        m->devices[0] = ggml_backend_reg_dev_get(vulkan, 0);
        __android_log_print(ANDROID_LOG_INFO, "CaptionGlassNative", "MT device: %s (%s)",
                            ggml_backend_dev_name(m->devices[0]), ggml_backend_dev_description(m->devices[0]));
        auto mp = llama_model_default_params();
        mp.devices = m->devices;
        mp.n_gpu_layers = INT_MAX;
        mp.split_mode = LLAMA_SPLIT_MODE_NONE;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.progress_callback = [](float, void * c) { return !aborted(c); };
        mp.progress_callback_user_data = call;
        m->model = llama_model_load_from_file(bytes(env, path).c_str(), mp);
        if (!m->model) throw std::runtime_error("model_load_failed");
        check(call);
        auto cp = llama_context_default_params();
        // Small batches use Vulkan's vector kernels for short mobile prompts.
        // ponytail: poll between GPU batches; in-flight GPU work cannot be preempted.
        cp.n_ctx = 2048; cp.n_batch = batch_size; cp.n_ubatch = batch_size;
        cp.n_threads = 3; cp.n_threads_batch = 3;
        cp.offload_kqv = true; cp.op_offload = true;
        cp.no_perf = false;
        cp.abort_callback = aborted; cp.abort_callback_data = call;
        m->ctx = llama_init_from_model(m->model, cp);
        if (!m->ctx) throw std::runtime_error("context_load_failed");
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
                                                       jbyteArray prefix, jbyteArray input, jbyteArray ending, jbyteArray background, jboolean greedy, jlong token, jint limit, jobject progress) {
    auto * m = reinterpret_cast<Model *>(model);
    auto * call = reinterpret_cast<Call *>(token);
    if (!m->usable) {
        fail(env, std::runtime_error("vulkan_context_failed_restart_session"));
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
        llama_perf_context_reset(m->ctx);
        // Success and failure both wipe the cache before returning to the sole caller.
        llama_set_abort_callback(m->ctx, aborted, call);
        const auto * vocab = llama_model_get_vocab(m->model);
        // Only APK-owned template pieces parse role tokens. Speech/history remain literal.
        auto tokens = tokenize(vocab, bytes(env, prefix), true);
        auto history = tokenize(vocab, bytes(env, background), false);
        if (!history.empty()) {
            auto header = tokenize(vocab, "[Background Information]\n", false);
            auto separator = tokenize(vocab, "\n\n", false);
            tokens.insert(tokens.end(), header.begin(), header.end());
            tokens.insert(tokens.end(), history.end() - std::min<size_t>(history.size(), 256), history.end());
            tokens.insert(tokens.end(), separator.begin(), separator.end());
        }
        auto content = tokenize(vocab, bytes(env, input), false);
        auto suffix = tokenize(vocab, bytes(env, ending), true);
        tokens.insert(tokens.end(), content.begin(), content.end());
        tokens.insert(tokens.end(), suffix.begin(), suffix.end());
        if (limit < 1 || limit > 256 || tokens.size() + limit > 2048) throw std::runtime_error("context_budget");
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (greedy) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.f, 0.f));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.6f, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
        }
        auto batch = llama_batch_init(batch_size, 0, 1);
        struct BatchGuard { llama_batch b; ~BatchGuard() { llama_batch_free(b); } } guard{batch};
        int pos = 0;
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
        std::string output;
        bool complete = false;
        auto last_progress = Clock::time_point{};
        for (int i = 0; i < limit; ++i) {
            check(call);
            const auto t = llama_sampler_sample(sampler.get(), m->ctx, -1);
            if (llama_vocab_is_eog(vocab, t)) { complete = true; break; }
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
        llama_set_abort_callback(m->ctx, nullptr, nullptr);
        const auto clear_start = Clock::now();
        llama_memory_clear(llama_get_memory(m->ctx), true);
        m->clear_ms = std::chrono::duration<double, std::milli>(Clock::now() - clear_start).count();
        return output_bytes(env, output);
    } catch (const std::exception & e) {
        // Retain the call token and context until all submitted work has returned.
        llama_set_abort_callback(m->ctx, nullptr, nullptr);
        try {
            llama_synchronize(m->ctx);
            llama_memory_clear(llama_get_memory(m->ctx), true);
        } catch (const std::exception & cleanup) {
            m->usable = false;
            __android_log_print(ANDROID_LOG_ERROR, "CaptionGlassNative", "MT cleanup failed: %s", cleanup.what());
        }
        fail(env, e); return nullptr;
    }
}
