// Minimal C shim over ONNX Runtime C API for Termux (no python onnxruntime binding).
// Lookup: ppocr6 only needs a single-input/single-output float32 session.
#include <onnxruntime_c_api.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const OrtApi* g = NULL;
static OrtEnv* g_env = NULL;
static char g_err[1024];

static void seterr(const char* what, OrtStatus* st) {
    const char* msg = st ? g->GetErrorMessage(st) : NULL;
    snprintf(g_err, sizeof(g_err), "%s: %s", what, msg ? msg : "unknown");
    if (st) g->ReleaseStatus(st);
}
const char* ort_last_error(void) { return g_err; }

static int ensure_env(void) {
    if (g) return 0;
    const OrtApiBase* base = OrtGetApiBase();
    if (!base) { snprintf(g_err, sizeof(g_err), "OrtGetApiBase returned NULL"); return -1; }
    g = base->GetApi(ORT_API_VERSION);
    if (!g) { snprintf(g_err, sizeof(g_err), "GetApi(%d) failed", ORT_API_VERSION); return -1; }
    OrtStatus* st = g->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "ppocr6", &g_env);
    if (st) { seterr("CreateEnv", st); return -1; }
    return 0;
}

typedef struct { OrtSession* s; char in_name[256]; char out_names[8][256]; size_t n_out; } ShimSession;

void* ort_open(const char* path, int threads) {
    if (ensure_env() != 0) return NULL;
    OrtSessionOptions* so = NULL;
    OrtStatus* st = g->CreateSessionOptions(&so);
    if (st) { seterr("CreateSessionOptions", st); return NULL; }
    g->SetIntraOpNumThreads(so, threads > 0 ? threads : 2);
    g->SetSessionGraphOptimizationLevel(so, ORT_ENABLE_ALL);
    OrtSession* s = NULL;
    st = g->CreateSession(g_env, path, so, &s);
    if (st) { seterr("CreateSession", st); g->ReleaseSessionOptions(so); return NULL; }
    g->ReleaseSessionOptions(so);
    ShimSession* h = (ShimSession*)calloc(1, sizeof(ShimSession));
    h->s = s;
    size_t n = 0;
    st = g->SessionGetInputCount(s, &n);
    if (st) { seterr("SessionGetInputCount", st); return NULL; }
    if (n < 1) { snprintf(g_err, sizeof(g_err), "model has no inputs"); return NULL; }
    OrtAllocator* alloc = NULL;
    st = g->GetAllocatorWithDefaultOptions(&alloc);
    if (st) { seterr("GetAllocatorWithDefaultOptions", st); return NULL; }
    char* name = NULL;
    st = g->SessionGetInputName(s, 0, alloc, &name);
    if (st) { seterr("SessionGetInputName", st); return NULL; }
    snprintf(h->in_name, sizeof(h->in_name), "%s", name);
    st = g->SessionGetOutputCount(s, &h->n_out);
    if (st) { seterr("SessionGetOutputCount", st); return NULL; }
    if (h->n_out > 8) h->n_out = 8;
    for (size_t i = 0; i < h->n_out; i++) {
        char* oname = NULL;
        st = g->SessionGetOutputName(s, i, alloc, &oname);
        if (st) { seterr("SessionGetOutputName", st); return NULL; }
        snprintf(h->out_names[i], sizeof(h->out_names[i]), "%s", oname);
    }
    return h;
}

void ort_close(void* handle) {
    ShimSession* h = (ShimSession*)handle;
    if (!h) return;
    if (h->s && g) g->ReleaseSession(h->s);
    free(h);
}

int ort_input_name(void* handle, char* buf, int buflen) {
    ShimSession* h = (ShimSession*)handle;
    if (!h || !buf || buflen <= 0) return -1;
    snprintf(buf, (size_t)buflen, "%s", h->in_name);
    return 0;
}

int ort_output_count(void* handle) {
    ShimSession* h = (ShimSession*)handle;
    return h ? (int)h->n_out : -1;
}

// Run first input -> first output. out_data is malloc'd; caller must call ort_free.
int ort_run_f32(void* handle, const float* data, const long long* shape, int ndim,
                float** out_data, long long* out_shape, int max_dims,
                int* out_ndim, long long* out_count) {
    ShimSession* h = (ShimSession*)handle;
    if (!h || !h->s) { snprintf(g_err, sizeof(g_err), "null session"); return -1; }
    OrtMemoryInfo* mem = NULL;
    OrtStatus* st = g->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem);
    if (st) { seterr("CreateCpuMemoryInfo", st); return -1; }
    long long numel = 1;
    for (int i = 0; i < ndim; i++) numel *= shape[i];
    OrtValue* in = NULL;
    st = g->CreateTensorWithDataAsOrtValue(
        mem, (void*)data, (size_t)(numel * sizeof(float)),
        (const int64_t*)shape, (size_t)ndim, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in);
    if (st) { seterr("CreateTensorWithDataAsOrtValue", st); g->ReleaseMemoryInfo(mem); return -1; }
    const char* in_names[1] = { h->in_name };
    const OrtValue* in_vals[1] = { in };
    const char* out_names[8];
    for (size_t i = 0; i < h->n_out; i++) out_names[i] = h->out_names[i];
    OrtValue* outs[8] = {0};
    st = g->Run(h->s, NULL, in_names, in_vals, 1, out_names, h->n_out, outs);
    if (st) { seterr("Run", st); g->ReleaseValue(in); g->ReleaseMemoryInfo(mem); return -1; }
    OrtValue* out = outs[0];
    float* fdata = NULL;
    st = g->GetTensorMutableData(out, (void**)&fdata);
    if (st) { seterr("GetTensorMutableData", st); g->ReleaseValue(out); g->ReleaseValue(in); g->ReleaseMemoryInfo(mem); return -1; }
    OrtTensorTypeAndShapeInfo* info = NULL;
    st = g->GetTensorTypeAndShape(out, &info);
    if (st) { seterr("GetTensorTypeAndShape", st); g->ReleaseValue(out); g->ReleaseValue(in); g->ReleaseMemoryInfo(mem); return -1; }
    size_t nd = 0;
    g->GetDimensionsCount(info, &nd);
    if ((int)nd > max_dims) nd = (size_t)max_dims;
    g->GetDimensions(info, (int64_t*)out_shape, nd);
    size_t cnt = 0;
    g->GetTensorShapeElementCount(info, &cnt);
    *out_ndim = (int)nd;
    *out_count = (long long)cnt;
    float* copy = (float*)malloc(sizeof(float) * (size_t)cnt);
    if (!copy) { snprintf(g_err, sizeof(g_err), "out of memory"); g->ReleaseTensorTypeAndShapeInfo(info); g->ReleaseValue(out); g->ReleaseValue(in); g->ReleaseMemoryInfo(mem); return -1; }
    memcpy(copy, fdata, sizeof(float) * (size_t)cnt);
    *out_data = copy;
    g->ReleaseTensorTypeAndShapeInfo(info);
    g->ReleaseValue(out);
    g->ReleaseValue(in);
    g->ReleaseMemoryInfo(mem);
    return 0;
}

void ort_free(void* p) { free(p); }
