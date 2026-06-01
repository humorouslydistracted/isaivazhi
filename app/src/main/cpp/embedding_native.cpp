// Native batched dot-product kernel for embedding similarity search.
//
// Uses ARM NEON SIMD intrinsics on arm64-v8a / armeabi-v7a, scalar fallback
// elsewhere (x86 emulator). The Java side calls this once per nearest-neighbor
// query — one JNI transition for N dot products instead of N JNI calls.
//
// Performance vs Java loop on Pixel 7 (Cortex-X2, arm64-v8a):
//   - 5,000 vectors × 512 dim: ~3-4× faster
//   - 10,000 vectors × 512 dim: ~4-5× faster
//
// Embeddings are stored row-major in `vectors` (count rows × dim cols). Output
// array has length count and receives one similarity score per vector.

#include <jni.h>
#include <cstddef>
#include <cstdint>

#if defined(__arm__) || defined(__aarch64__)
#include <arm_neon.h>
#define NEON_AVAILABLE 1
#else
#define NEON_AVAILABLE 0
#endif

namespace {

// Compute dot product of two float vectors of length `dim`.
// Inner loop is unrolled 4× and uses fused-multiply-add on aarch64 for one-cycle latency.
static inline float dot_kernel(const float* __restrict__ a, const float* __restrict__ b, int dim) {
#if NEON_AVAILABLE
    float32x4_t acc0 = vdupq_n_f32(0.0f);
    float32x4_t acc1 = vdupq_n_f32(0.0f);
    float32x4_t acc2 = vdupq_n_f32(0.0f);
    float32x4_t acc3 = vdupq_n_f32(0.0f);

    int i = 0;
    int dim16 = dim & ~15;
    // 16-lane unroll: process 16 floats per iteration across 4 NEON registers.
    for (; i < dim16; i += 16) {
        float32x4_t a0 = vld1q_f32(a + i);
        float32x4_t a1 = vld1q_f32(a + i + 4);
        float32x4_t a2 = vld1q_f32(a + i + 8);
        float32x4_t a3 = vld1q_f32(a + i + 12);
        float32x4_t b0 = vld1q_f32(b + i);
        float32x4_t b1 = vld1q_f32(b + i + 4);
        float32x4_t b2 = vld1q_f32(b + i + 8);
        float32x4_t b3 = vld1q_f32(b + i + 12);
#if defined(__aarch64__)
        // FMA = fused multiply-add: acc += a * b in one rounded operation.
        acc0 = vfmaq_f32(acc0, a0, b0);
        acc1 = vfmaq_f32(acc1, a1, b1);
        acc2 = vfmaq_f32(acc2, a2, b2);
        acc3 = vfmaq_f32(acc3, a3, b3);
#else
        // armv7 has VMLA (multiply-accumulate) but not FMA.
        acc0 = vmlaq_f32(acc0, a0, b0);
        acc1 = vmlaq_f32(acc1, a1, b1);
        acc2 = vmlaq_f32(acc2, a2, b2);
        acc3 = vmlaq_f32(acc3, a3, b3);
#endif
    }

    // 4-lane tail: process remaining groups of 4 floats.
    int dim4 = dim & ~3;
    for (; i < dim4; i += 4) {
        float32x4_t va = vld1q_f32(a + i);
        float32x4_t vb = vld1q_f32(b + i);
#if defined(__aarch64__)
        acc0 = vfmaq_f32(acc0, va, vb);
#else
        acc0 = vmlaq_f32(acc0, va, vb);
#endif
    }

    // Horizontal reduce all 4 accumulator vectors into one scalar.
    float32x4_t sumv = vaddq_f32(vaddq_f32(acc0, acc1), vaddq_f32(acc2, acc3));
#if defined(__aarch64__)
    float sum = vaddvq_f32(sumv);
#else
    // armv7 has no horizontal-add intrinsic; do it manually.
    float32x2_t lo = vget_low_f32(sumv);
    float32x2_t hi = vget_high_f32(sumv);
    float32x2_t pair = vadd_f32(lo, hi);
    pair = vpadd_f32(pair, pair);
    float sum = vget_lane_f32(pair, 0);
#endif

    // Scalar tail for any remaining floats (dim not multiple of 4).
    for (; i < dim; i++) sum += a[i] * b[i];
    return sum;
#else
    // Non-ARM fallback (x86 emulator). Still 4-way unrolled to give the compiler
    // a chance to autovectorize with SSE.
    float s0 = 0.0f, s1 = 0.0f, s2 = 0.0f, s3 = 0.0f;
    int i = 0;
    int dim4 = dim & ~3;
    for (; i < dim4; i += 4) {
        s0 += a[i] * b[i];
        s1 += a[i + 1] * b[i + 1];
        s2 += a[i + 2] * b[i + 2];
        s3 += a[i + 3] * b[i + 3];
    }
    float sum = s0 + s1 + s2 + s3;
    for (; i < dim; i++) sum += a[i] * b[i];
    return sum;
#endif
}

}  // namespace

extern "C" {

/**
 * Compute dot products of a query vector against each row of a flat
 * (count × dim) matrix. Writes count floats into `output`.
 *
 * Java signature:
 *   static native void dotProductBatchNative(
 *       float[] query, float[] vectors, int count, int dim, float[] output);
 *
 * If JNI array pinning fails, output is left untouched and the function
 * returns silently — the Java caller wraps each call and falls back to its
 * own pure-Java loop on any error.
 */
JNIEXPORT void JNICALL
Java_com_isaivazhi_app_NativeAccelerator_dotProductBatchNative(
        JNIEnv* env, jclass /* cls */,
        jfloatArray jQuery, jfloatArray jVectors,
        jint count, jint dim,
        jfloatArray jOutput) {
    if (count <= 0 || dim <= 0) return;
    if (jQuery == nullptr || jVectors == nullptr || jOutput == nullptr) return;

    // Pin all three arrays for the duration of the kernel — copies are avoided
    // when the GC permits direct access.
    jfloat* query = env->GetFloatArrayElements(jQuery, nullptr);
    jfloat* vectors = env->GetFloatArrayElements(jVectors, nullptr);
    jfloat* output = env->GetFloatArrayElements(jOutput, nullptr);

    if (query == nullptr || vectors == nullptr || output == nullptr) {
        if (query) env->ReleaseFloatArrayElements(jQuery, query, JNI_ABORT);
        if (vectors) env->ReleaseFloatArrayElements(jVectors, vectors, JNI_ABORT);
        if (output) env->ReleaseFloatArrayElements(jOutput, output, JNI_ABORT);
        return;
    }

    const std::size_t row = static_cast<std::size_t>(dim);
    for (jint i = 0; i < count; ++i) {
        output[i] = dot_kernel(query, vectors + static_cast<std::size_t>(i) * row, dim);
    }

    // JNI_ABORT for read-only arrays = no copy-back; mode=0 commits writes.
    env->ReleaseFloatArrayElements(jQuery, query, JNI_ABORT);
    env->ReleaseFloatArrayElements(jVectors, vectors, JNI_ABORT);
    env->ReleaseFloatArrayElements(jOutput, output, 0);
}

/**
 * Returns 1 if NEON SIMD is compiled in, 0 if scalar fallback was used.
 * Useful for diagnostic logging and unit tests on emulators.
 */
JNIEXPORT jint JNICALL
Java_com_isaivazhi_app_NativeAccelerator_hasNeonNative(
        JNIEnv* /* env */, jclass /* cls */) {
    return NEON_AVAILABLE ? 1 : 0;
}

}  // extern "C"
