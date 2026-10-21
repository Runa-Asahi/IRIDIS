// libiris_imagegen.so — thin QNN (Qualcomm AI Engine Direct) runner for AI Hub context binaries.
//
// Native only loads QNN and executes the graphs of a context binary; the diffusion loop
// (tokenizer, scheduler, CFG, quantisation) lives in Kotlin (imagegen/QnnSdEngine.kt).
//
// Nothing Qualcomm-specific is linked: libQnnHtp / libQnnSystem / libcdsprpc are opened with
// dlopen and every entry point is resolved with dlsym, so this library loads (and reports
// "unsupported") on any arm64 device.
//
// JNI surface (com.iris.localai.imagegen.QnnNative):
//   nativeProbe(runtimeDir) -> JSON {backendOk, deviceOk, error, cdsp:{present, unsignedPd, arch}}
//   nativeOpen(runtimeDir, binPath) -> handle (0 on failure; nativeLastError() says why)
//   nativeInfo(handle) -> JSON {graph, inputs:[…], outputs:[…]} (name, dims, dtype, bytes, scale, offset)
//   nativeExecute(handle, ByteBuffer[] inputs, ByteBuffer[] outputs) -> null or error string
//   nativeClose(handle)

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "QnnInterface.h"
#include "QnnTypes.h"
#include "System/QnnSystemInterface.h"
#include "System/QnnSystemContext.h"

#define TAG "iris_imagegen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------- small helpers

std::string fmt(const char* f, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, f);
    vsnprintf(buf, sizeof buf, f, ap);
    va_end(ap);
    return buf;
}

std::string jsonEscape(const std::string& s) {
    std::string o;
    o.reserve(s.size() + 2);
    for (char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if ((unsigned char) c < 0x20) o += fmt("\\u%04x", c);
                else o += c;
        }
    }
    return o;
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

/** Bits of a QNN data type: the low byte holds the width as two decimal digits in hex (0x32 = 32). */
size_t dtypeBytes(Qnn_DataType_t t) {
    unsigned low = (unsigned) t & 0xFFu;
    unsigned bits = (low >> 4) * 10 + (low & 0xFu);
    return bits >= 8 ? bits / 8 : 1;
}

const char* dtypeName(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_INT_8: return "int8";
        case QNN_DATATYPE_INT_16: return "int16";
        case QNN_DATATYPE_INT_32: return "int32";
        case QNN_DATATYPE_INT_64: return "int64";
        case QNN_DATATYPE_UINT_8: return "uint8";
        case QNN_DATATYPE_UINT_16: return "uint16";
        case QNN_DATATYPE_UINT_32: return "uint32";
        case QNN_DATATYPE_UINT_64: return "uint64";
        case QNN_DATATYPE_FLOAT_16: return "float16";
        case QNN_DATATYPE_FLOAT_32: return "float32";
        case QNN_DATATYPE_SFIXED_POINT_8: return "sfixed8";
        case QNN_DATATYPE_SFIXED_POINT_16: return "sfixed16";
        case QNN_DATATYPE_SFIXED_POINT_32: return "sfixed32";
        case QNN_DATATYPE_UFIXED_POINT_8: return "ufixed8";
        case QNN_DATATYPE_UFIXED_POINT_16: return "ufixed16";
        case QNN_DATATYPE_UFIXED_POINT_32: return "ufixed32";
        case QNN_DATATYPE_BOOL_8: return "bool8";
        default: return "unknown";
    }
}

// --------------------------------------------------------------- tensor accessors (v1 / v2 share a prefix)

struct TensorView {
    Qnn_Tensor_t* t;
    bool v2() const { return t->version == QNN_TENSOR_VERSION_2; }
    const char* name() const { return v2() ? t->v2.name : t->v1.name; }
    Qnn_DataType_t dtype() const { return v2() ? t->v2.dataType : t->v1.dataType; }
    const Qnn_QuantizeParams_t& quant() const { return v2() ? t->v2.quantizeParams : t->v1.quantizeParams; }
    uint32_t rank() const { return v2() ? t->v2.rank : t->v1.rank; }
    uint32_t* dims() const { return v2() ? t->v2.dimensions : t->v1.dimensions; }
    void setDims(uint32_t* d) { if (v2()) t->v2.dimensions = d; else t->v1.dimensions = d; }
    void setName(const char* n) { if (v2()) t->v2.name = n; else t->v1.name = n; }
    void setRaw(void* data, uint32_t size) {
        if (v2()) {
            t->v2.memType = QNN_TENSORMEMTYPE_RAW;
            t->v2.clientBuf.data = data;
            t->v2.clientBuf.dataSize = size;
            t->v2.isDynamicDimensions = nullptr;
        } else {
            t->v1.memType = QNN_TENSORMEMTYPE_RAW;
            t->v1.clientBuf.data = data;
            t->v1.clientBuf.dataSize = size;
        }
    }
};

/** A graph input/output: a private copy of the descriptor from the binary info, with owned name/dims. */
struct IoTensor {
    std::string name;
    std::vector<uint32_t> dims;
    Qnn_Tensor_t desc{};
    Qnn_DataType_t dtype = QNN_DATATYPE_UNDEFINED;
    size_t bytes = 0;
    bool hasScale = false;
    float scale = 0.f;
    int32_t offset = 0;
};

IoTensor copyTensor(const Qnn_Tensor_t& src) {
    IoTensor io;
    io.desc = src;
    TensorView v{&io.desc};
    io.name = v.name() ? v.name() : "";
    io.dims.assign(v.dims(), v.dims() + v.rank());
    io.dtype = v.dtype();
    size_t n = 1;
    for (uint32_t d : io.dims) n *= d;
    io.bytes = n * dtypeBytes(io.dtype);
    const Qnn_QuantizeParams_t& q = v.quant();
    if (q.encodingDefinition == QNN_DEFINITION_DEFINED &&
        q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        io.hasScale = true;
        io.scale = q.scaleOffsetEncoding.scale;
        io.offset = q.scaleOffsetEncoding.offset;
    }
    if (v.v2()) {
        io.desc.v2.isDynamicDimensions = nullptr;
        io.desc.v2.isProduced = 0;
    }
    return io;
}

// Pointers inside IoTensor::desc must point at the IoTensor's own storage (fixed after the vector stops growing).
void fixPointers(IoTensor& io) {
    TensorView v{&io.desc};
    v.setName(io.name.c_str());
    v.setDims(io.dims.data());
}

// ---------------------------------------------------------------- QNN runtime (one per process)

typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t*** list, uint32_t* n);
typedef Qnn_ErrorHandle_t (*GetSysProvidersFn)(const QnnSystemInterface_t*** list, uint32_t* n);

void qnnLog(const char* f, QnnLog_Level_t level, uint64_t, va_list args) {
    int prio = level == QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == QNN_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_vprint(prio, "iris_qnn", f, args);
}

struct Runtime {
    std::string dir;
    void* htpLib = nullptr;
    void* sysLib = nullptr;
    std::vector<void*> preloaded;
    QNN_INTERFACE_VER_TYPE qnn{};
    QNN_SYSTEM_INTERFACE_VER_TYPE sys{};
    Qnn_LogHandle_t log = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    int refs = 0;
};

std::mutex gMu;          // guards gRt + its refcount
Runtime* gRt = nullptr;
thread_local std::string gLastError;

void freeRuntime(Runtime* rt) {
    if (!rt) return;
    if (rt->device && rt->qnn.deviceFree) rt->qnn.deviceFree(rt->device);
    if (rt->backend && rt->qnn.backendFree) rt->qnn.backendFree(rt->backend);
    if (rt->log && rt->qnn.logFree) rt->qnn.logFree(rt->log);
    // The QNN libraries stay loaded: unloading libQnnHtp while FastRPC threads linger is a known crash source.
    delete rt;
}

void* openIn(const std::string& dir, const char* name, int flags, std::string& err) {
    std::string path = dir + "/" + name;
    void* h = dlopen(path.c_str(), flags);
    if (!h) {
        const char* e = dlerror();
        err = fmt("dlopen %s: %s", name, e ? e : "?");
    }
    return h;
}

/**
 * Loads the QNN HTP backend from [dir] and creates the backend + HTP device.
 * stage: 0 none, 1 libraries + interfaces, 2 backend, 3 device. Returns nullptr with err set on failure.
 */
Runtime* createRuntime(const std::string& dir, std::string& err, int* stage) {
    *stage = 0;
    // The DSP side (libQnnHtpV<arch>Skel.so) is found by FastRPC through ADSP_LIBRARY_PATH.
    std::string adsp = dir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    auto rt = std::make_unique<Runtime>();
    rt->dir = dir;

    // libQnnHtp dlopens its stub by soname; preloading it by absolute path makes the linker
    // resolve that soname to the already-loaded copy (the app-private dir is not on the search path).
    if (DIR* d = opendir(dir.c_str())) {
        while (dirent* e = readdir(d)) {
            std::string n = e->d_name;
            if (n.rfind("libQnnHtpV", 0) == 0 && n.size() > 7 && n.compare(n.size() - 7, 7, "Stub.so") == 0) {
                std::string ignore;
                if (void* h = openIn(dir, n.c_str(), RTLD_NOW | RTLD_GLOBAL, ignore)) rt->preloaded.push_back(h);
            }
        }
        closedir(d);
    }

    rt->sysLib = openIn(dir, "libQnnSystem.so", RTLD_NOW | RTLD_LOCAL, err);
    if (!rt->sysLib) return nullptr;
    rt->htpLib = openIn(dir, "libQnnHtp.so", RTLD_NOW | RTLD_GLOBAL, err);
    if (!rt->htpLib) return nullptr;

    auto getProviders = (GetProvidersFn) dlsym(rt->htpLib, "QnnInterface_getProviders");
    auto getSysProviders = (GetSysProvidersFn) dlsym(rt->sysLib, "QnnSystemInterface_getProviders");
    if (!getProviders || !getSysProviders) { err = "QNN getProviders symbol missing"; return nullptr; }

    const QnnInterface_t** providers = nullptr;
    uint32_t n = 0;
    if (getProviders(&providers, &n) != QNN_SUCCESS || !providers || n == 0) { err = "QnnInterface_getProviders failed"; return nullptr; }
    const QnnInterface_t* best = nullptr;
    for (uint32_t i = 0; i < n; i++) {
        const QnnInterface_t* p = providers[i];
        if (!p || p->apiVersion.coreApiVersion.major != QNN_API_VERSION_MAJOR) continue;
        if (!best || p->apiVersion.coreApiVersion.minor > best->apiVersion.coreApiVersion.minor) best = p;
    }
    if (!best) {
        err = fmt("no QNN provider with core API %d.x (first: %u.%u)", QNN_API_VERSION_MAJOR,
                  providers[0]->apiVersion.coreApiVersion.major, providers[0]->apiVersion.coreApiVersion.minor);
        return nullptr;
    }
    rt->qnn = best->QNN_INTERFACE_VER_NAME;
    LOGI("QNN provider %s core API %u.%u.%u (headers %d.%d)", best->providerName ? best->providerName : "?",
         best->apiVersion.coreApiVersion.major, best->apiVersion.coreApiVersion.minor,
         best->apiVersion.coreApiVersion.patch, QNN_API_VERSION_MAJOR, QNN_API_VERSION_MINOR);

    const QnnSystemInterface_t** sysProviders = nullptr;
    n = 0;
    if (getSysProviders(&sysProviders, &n) != QNN_SUCCESS || !sysProviders || n == 0) { err = "QnnSystemInterface_getProviders failed"; return nullptr; }
    const QnnSystemInterface_t* bestSys = nullptr;
    for (uint32_t i = 0; i < n; i++) {
        const QnnSystemInterface_t* p = sysProviders[i];
        if (!p || p->systemApiVersion.major != QNN_SYSTEM_API_VERSION_MAJOR) continue;
        if (!bestSys || p->systemApiVersion.minor > bestSys->systemApiVersion.minor) bestSys = p;
    }
    if (!bestSys) { err = "no QNN System provider with a matching API version"; return nullptr; }
    rt->sys = bestSys->QNN_SYSTEM_INTERFACE_VER_NAME;
    *stage = 1;

    if (rt->qnn.logCreate) rt->qnn.logCreate(qnnLog, QNN_LOG_LEVEL_WARN, &rt->log);
    if (!rt->qnn.backendCreate) { err = "backendCreate missing"; return nullptr; }
    Qnn_ErrorHandle_t e = rt->qnn.backendCreate(rt->log, nullptr, &rt->backend);
    if (e != QNN_SUCCESS || !rt->backend) { err = fmt("backendCreate failed (%lu)", (unsigned long) e); freeRuntime(rt.release()); return nullptr; }
    *stage = 2;
    if (rt->qnn.deviceCreate) {
        e = rt->qnn.deviceCreate(rt->log, nullptr, &rt->device);
        if (e != QNN_SUCCESS) { err = fmt("deviceCreate failed (%lu)", (unsigned long) e); freeRuntime(rt.release()); return nullptr; }
    }
    *stage = 3;
    return rt.release();
}

Runtime* acquireRuntime(const std::string& dir, std::string& err) {
    std::lock_guard<std::mutex> lk(gMu);
    if (gRt) {
        if (gRt->dir != dir) { err = "QNN runtime already loaded from " + gRt->dir; return nullptr; }
        gRt->refs++;
        return gRt;
    }
    int stage = 0;
    gRt = createRuntime(dir, err, &stage);
    if (gRt) gRt->refs = 1;
    return gRt;
}

void releaseRuntime(Runtime* rt) {
    std::lock_guard<std::mutex> lk(gMu);
    if (rt != gRt || --gRt->refs > 0) return;
    freeRuntime(gRt);
    gRt = nullptr;
}

// ---------------------------------------------------------------- one context binary = one model

struct Model {
    Runtime* rt = nullptr;
    Qnn_ContextHandle_t ctx = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    std::string graphName;
    std::vector<IoTensor> inputs, outputs;
    std::mutex mu;
};

struct Mapped {
    void* p = MAP_FAILED;
    size_t n = 0;
    ~Mapped() { if (p != MAP_FAILED) munmap(p, n); }
};

bool mapFile(const std::string& path, Mapped& m, std::string& err) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) { err = fmt("open %s: %s", path.c_str(), strerror(errno)); return false; }
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); err = "empty context binary"; return false; }
    m.n = (size_t) st.st_size;
    m.p = mmap(nullptr, m.n, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (m.p == MAP_FAILED) { err = fmt("mmap: %s", strerror(errno)); return false; }
    return true;
}

template <typename G>
void takeGraph(const G& g, Model& m) {
    m.graphName = g.graphName ? g.graphName : "";
    for (uint32_t i = 0; i < g.numGraphInputs; i++) m.inputs.push_back(copyTensor(g.graphInputs[i]));
    for (uint32_t i = 0; i < g.numGraphOutputs; i++) m.outputs.push_back(copyTensor(g.graphOutputs[i]));
}

bool readBinaryInfo(Runtime* rt, Mapped& bin, Model& m, std::string& err) {
    if (!rt->sys.systemContextCreate || !rt->sys.systemContextGetBinaryInfo || !rt->sys.systemContextFree) {
        err = "QNN System context API missing"; return false;
    }
    QnnSystemContext_Handle_t sc = nullptr;
    if (rt->sys.systemContextCreate(&sc) != QNN_SUCCESS || !sc) { err = "systemContextCreate failed"; return false; }
    const QnnSystemContext_BinaryInfo_t* info = nullptr;
    Qnn_ContextBinarySize_t infoSize = 0;
    Qnn_ErrorHandle_t e = rt->sys.systemContextGetBinaryInfo(sc, bin.p, bin.n, &info, &infoSize);
    bool ok = false;
    if (e != QNN_SUCCESS || !info) {
        err = fmt("getBinaryInfo failed (%lu)", (unsigned long) e);
    } else {
        uint32_t numGraphs = 0;
        QnnSystemContext_GraphInfo_t* graphs = nullptr;
        switch (info->version) {
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1: numGraphs = info->contextBinaryInfoV1.numGraphs; graphs = info->contextBinaryInfoV1.graphs; break;
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2: numGraphs = info->contextBinaryInfoV2.numGraphs; graphs = info->contextBinaryInfoV2.graphs; break;
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3: numGraphs = info->contextBinaryInfoV3.numGraphs; graphs = info->contextBinaryInfoV3.graphs; break;
            default: break;
        }
        if (numGraphs == 0 || !graphs) {
            err = fmt("context binary has no graphs (info version %d)", (int) info->version);
        } else {
            if (numGraphs > 1) LOGI("context binary has %u graphs; using the first", numGraphs);
            const QnnSystemContext_GraphInfo_t& g = graphs[0];
            switch (g.version) {
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1: takeGraph(g.graphInfoV1, m); ok = true; break;
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2: takeGraph(g.graphInfoV2, m); ok = true; break;
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3: takeGraph(g.graphInfoV3, m); ok = true; break;
                default: err = fmt("unknown graph info version %d", (int) g.version);
            }
        }
    }
    // Names/dims were deep-copied; nothing below keeps pointers into the system context.
    for (auto& t : m.inputs) fixPointers(t);
    for (auto& t : m.outputs) fixPointers(t);
    rt->sys.systemContextFree(sc);
    return ok;
}

Model* openModel(const std::string& dir, const std::string& binPath, std::string& err) {
    Runtime* rt = acquireRuntime(dir, err);
    if (!rt) return nullptr;
    auto m = std::make_unique<Model>();
    m->rt = rt;
    {
        Mapped bin;
        if (!mapFile(binPath, bin, err) || !readBinaryInfo(rt, bin, *m, err)) { releaseRuntime(rt); return nullptr; }
        if (!rt->qnn.contextCreateFromBinary || !rt->qnn.graphRetrieve || !rt->qnn.graphExecute) {
            err = "QNN context/graph API missing"; releaseRuntime(rt); return nullptr;
        }
        Qnn_ErrorHandle_t e = rt->qnn.contextCreateFromBinary(rt->backend, rt->device, nullptr, bin.p,
                                                               (Qnn_ContextBinarySize_t) bin.n, &m->ctx, nullptr);
        if (e != QNN_SUCCESS || !m->ctx) {
            err = fmt("contextCreateFromBinary failed (%lu)", (unsigned long) e);
            releaseRuntime(rt);
            return nullptr;
        }
    } // the binary is unmapped here: QNN keeps its own copy of what it needs
    Qnn_ErrorHandle_t e = rt->qnn.graphRetrieve(m->ctx, m->graphName.c_str(), &m->graph);
    if (e != QNN_SUCCESS || !m->graph) {
        err = fmt("graphRetrieve(%s) failed (%lu)", m->graphName.c_str(), (unsigned long) e);
        if (rt->qnn.contextFree) rt->qnn.contextFree(m->ctx, nullptr);
        releaseRuntime(rt);
        return nullptr;
    }
    LOGI("opened %s: graph %s, %zu inputs, %zu outputs", binPath.c_str(), m->graphName.c_str(), m->inputs.size(), m->outputs.size());
    return m.release();
}

void closeModel(Model* m) {
    if (!m) return;
    {
        std::lock_guard<std::mutex> lk(m->mu);
        if (m->ctx && m->rt->qnn.contextFree) m->rt->qnn.contextFree(m->ctx, nullptr);
        m->ctx = nullptr;
    }
    releaseRuntime(m->rt);
    delete m;
}

void appendTensors(std::string& o, const std::vector<IoTensor>& ts) {
    o += "[";
    for (size_t i = 0; i < ts.size(); i++) {
        const IoTensor& t = ts[i];
        if (i) o += ",";
        o += "{\"name\":\"" + jsonEscape(t.name) + "\",\"dims\":[";
        for (size_t d = 0; d < t.dims.size(); d++) o += (d ? "," : "") + std::to_string(t.dims[d]);
        o += "],\"dtype\":\"";
        o += dtypeName(t.dtype);
        o += "\",\"bytes\":" + std::to_string(t.bytes);
        if (t.hasScale) o += fmt(",\"scale\":%.9g,\"offset\":%d", t.scale, t.offset);
        o += "}";
    }
    o += "]";
}

std::string modelInfo(Model* m) {
    std::string o = "{\"graph\":\"" + jsonEscape(m->graphName) + "\",\"inputs\":";
    appendTensors(o, m->inputs);
    o += ",\"outputs\":";
    appendTensors(o, m->outputs);
    o += "}";
    return o;
}

// ---------------------------------------------------------------- CDSP facts via FastRPC (libcdsprpc.so)

// From the Hexagon SDK's remote.h (public FastRPC ABI).
constexpr uint32_t kDsprpcGetDspInfo = 2;   // handle_control_req_id DSPRPC_GET_DSP_INFO
constexpr uint32_t kAttrUnsignedPd = 1;     // remote_dsp_attributes UNSIGNED_PD_SUPPORT
constexpr uint32_t kAttrArchVer = 6;        // remote_dsp_attributes ARCH_VER
constexpr uint32_t kCdspDomain = 3;         // CDSP_DOMAIN_ID
struct DspCapability { uint32_t domain; uint32_t attribute_ID; uint32_t capability; };
typedef int (*RemoteHandleControlFn)(uint32_t req, void* data, uint32_t len);

struct CdspInfo { bool present = false; bool unsignedPd = false; int arch = 0; std::string error; };

CdspInfo queryCdsp() {
    CdspInfo r;
    void* h = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) { const char* e = dlerror(); r.error = fmt("libcdsprpc.so: %s", e ? e : "not found"); return r; }
    auto ctl = (RemoteHandleControlFn) dlsym(h, "remote_handle_control");
    if (!ctl) { r.error = "remote_handle_control missing"; return r; } // keep the library loaded
    r.present = true;
    DspCapability cap{kCdspDomain, kAttrArchVer, 0};
    int err = ctl(kDsprpcGetDspInfo, &cap, sizeof cap);
    if (err == 0) {
        uint32_t v = cap.capability & 0xFFu;   // 0x79 -> v79
        r.arch = (int) ((v >> 4) * 10 + (v & 0xFu));
    } else {
        r.error = fmt("ARCH_VER query failed (%d)", err);
    }
    cap = DspCapability{kCdspDomain, kAttrUnsignedPd, 0};
    err = ctl(kDsprpcGetDspInfo, &cap, sizeof cap);
    if (err == 0) r.unsignedPd = cap.capability != 0;
    else if (r.error.empty()) r.error = fmt("UNSIGNED_PD query failed (%d)", err);
    return r;
}

std::string probe(const std::string& dir) {
    CdspInfo c = queryCdsp();
    bool backendOk = false, deviceOk = false;
    std::string err;
    {
        std::lock_guard<std::mutex> lk(gMu);
        if (gRt) {
            backendOk = gRt->backend != nullptr;
            deviceOk = true;
        } else {
            int stage = 0;
            Runtime* rt = createRuntime(dir, err, &stage);
            backendOk = stage >= 2;
            deviceOk = stage >= 3;
            freeRuntime(rt);
        }
    }
    std::string o = "{\"backendOk\":";
    o += backendOk ? "true" : "false";
    o += ",\"deviceOk\":";
    o += deviceOk ? "true" : "false";
    o += ",\"error\":";
    o += err.empty() ? "null" : "\"" + jsonEscape(err) + "\"";
    o += fmt(",\"cdsp\":{\"present\":%s,\"unsignedPd\":%s,\"arch\":%d", c.present ? "true" : "false",
             c.unsignedPd ? "true" : "false", c.arch);
    o += ",\"error\":";
    o += c.error.empty() ? "null" : "\"" + jsonEscape(c.error) + "\"";
    o += "}}";
    return o;
}

std::string execute(JNIEnv* env, Model* m, jobjectArray ins, jobjectArray outs) {
    std::lock_guard<std::mutex> lk(m->mu);
    if (!m->ctx) return "model closed";
    jsize ni = ins ? env->GetArrayLength(ins) : 0, no = outs ? env->GetArrayLength(outs) : 0;
    if ((size_t) ni != m->inputs.size()) return fmt("expected %zu inputs, got %d", m->inputs.size(), ni);
    if ((size_t) no != m->outputs.size()) return fmt("expected %zu outputs, got %d", m->outputs.size(), no);
    std::vector<Qnn_Tensor_t> qi(ni), qo(no);
    auto bind = [&](jobjectArray arr, jsize i, const IoTensor& io, Qnn_Tensor_t& dst) -> std::string {
        jobject buf = env->GetObjectArrayElement(arr, i);
        void* p = buf ? env->GetDirectBufferAddress(buf) : nullptr;
        jlong cap = buf ? env->GetDirectBufferCapacity(buf) : -1;
        if (buf) env->DeleteLocalRef(buf);
        if (!p) return io.name + ": not a direct ByteBuffer";
        if ((size_t) cap < io.bytes) return fmt("%s: buffer %lld < %zu bytes", io.name.c_str(), (long long) cap, io.bytes);
        dst = io.desc;
        TensorView{&dst}.setRaw(p, (uint32_t) io.bytes);
        return {};
    };
    for (jsize i = 0; i < ni; i++) { std::string e = bind(ins, i, m->inputs[i], qi[i]); if (!e.empty()) return e; }
    for (jsize i = 0; i < no; i++) { std::string e = bind(outs, i, m->outputs[i], qo[i]); if (!e.empty()) return e; }
    Qnn_ErrorHandle_t e = m->rt->qnn.graphExecute(m->graph, qi.data(), (uint32_t) ni, qo.data(), (uint32_t) no, nullptr, nullptr);
    if (e != QNN_SUCCESS) return fmt("graphExecute(%s) failed (%lu)", m->graphName.c_str(), (unsigned long) e);
    return {};
}

} // namespace

// ---------------------------------------------------------------- JNI

#define JNI_FN(name) Java_com_iris_localai_imagegen_QnnNative_##name

extern "C" {

JNIEXPORT jstring JNICALL JNI_FN(nativeProbe)(JNIEnv* env, jobject, jstring runtimeDir) {
    return env->NewStringUTF(probe(jstr(env, runtimeDir)).c_str());
}

JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv* env, jobject, jstring runtimeDir, jstring binPath) {
    std::string err;
    Model* m = openModel(jstr(env, runtimeDir), jstr(env, binPath), err);
    if (!m) { gLastError = err; LOGE("open failed: %s", err.c_str()); return 0; }
    gLastError.clear();
    return (jlong) (intptr_t) m;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeLastError)(JNIEnv* env, jobject) {
    return gLastError.empty() ? nullptr : env->NewStringUTF(gLastError.c_str());
}

JNIEXPORT jstring JNICALL JNI_FN(nativeInfo)(JNIEnv* env, jobject, jlong handle) {
    auto* m = (Model*) (intptr_t) handle;
    if (!m) return nullptr;
    return env->NewStringUTF(modelInfo(m).c_str());
}

JNIEXPORT jstring JNICALL JNI_FN(nativeExecute)(JNIEnv* env, jobject, jlong handle, jobjectArray inputs, jobjectArray outputs) {
    auto* m = (Model*) (intptr_t) handle;
    if (!m) return env->NewStringUTF("invalid handle");
    std::string e = execute(env, m, inputs, outputs);
    return e.empty() ? nullptr : env->NewStringUTF(e.c_str());
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv*, jobject, jlong handle) {
    closeModel((Model*) (intptr_t) handle);
}

} // extern "C"
