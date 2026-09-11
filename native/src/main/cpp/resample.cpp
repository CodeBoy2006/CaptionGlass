#include <jni.h>
#include "sherpa-onnx/csrc/resample.h"

#define JNI(name) Java_com_captionglass_nativebridge_AudioResample_##name
extern "C" JNIEXPORT jlong JNICALL JNI(create)(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new sherpa_onnx::LinearResample(48000, 16000, 7200, 6));
}
extern "C" JNIEXPORT void JNICALL JNI(release)(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<sherpa_onnx::LinearResample *>(handle);
}
extern "C" JNIEXPORT jfloatArray JNICALL JNI(process)(JNIEnv * env, jobject, jlong handle, jfloatArray input, jboolean flush) {
    std::vector<float> samples(env->GetArrayLength(input));
    env->GetFloatArrayRegion(input, 0, static_cast<jsize>(samples.size()), samples.data());
    std::vector<float> output;
    reinterpret_cast<sherpa_onnx::LinearResample *>(handle)->Resample(samples.data(), static_cast<int>(samples.size()), flush, &output);
    auto result = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}
