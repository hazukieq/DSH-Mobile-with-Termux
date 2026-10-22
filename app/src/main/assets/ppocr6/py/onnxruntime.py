"""Minimal onnxruntime shim for Termux (ctypes over libortshim.so).

Why: Termux's `onnxruntime` package ships the C/C++ library and headers but **no Python
binding** (its .deb contains no site-packages/onnxruntime), so `import onnxruntime` fails.
`libortshim.so` (compiled from onnxruntime_c_api.h against libonnxruntime.so) exposes a tiny
single-input/single-output float32 runner; this module gives ppocr6 the small API surface it uses.
"""
import ctypes
import os

import numpy as np

_HERE = os.path.dirname(os.path.abspath(__file__))
_lib = ctypes.CDLL(os.path.join(_HERE, "libortshim.so"))

_lib.ort_last_error.restype = ctypes.c_char_p
_lib.ort_open.restype = ctypes.c_void_p
_lib.ort_open.argtypes = [ctypes.c_char_p, ctypes.c_int]
_lib.ort_close.argtypes = [ctypes.c_void_p]
_lib.ort_input_name.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
_lib.ort_output_count.argtypes = [ctypes.c_void_p]
_lib.ort_run_f32.restype = ctypes.c_int
_lib.ort_run_f32.argtypes = [
    ctypes.c_void_p,
    ctypes.POINTER(ctypes.c_float),
    ctypes.POINTER(ctypes.c_longlong),
    ctypes.c_int,
    ctypes.POINTER(ctypes.POINTER(ctypes.c_float)),
    ctypes.POINTER(ctypes.c_longlong),
    ctypes.c_int,
    ctypes.POINTER(ctypes.c_int),
    ctypes.POINTER(ctypes.c_longlong),
]
_lib.ort_free.argtypes = [ctypes.c_void_p]


def _last_error():
    try:
        return _lib.ort_last_error().decode("utf-8", "replace")
    except Exception:
        return "unknown onnxruntime shim error"


class GraphOptimizationLevel:
    ORT_DISABLE_ALL = 0
    ORT_ENABLE_BASIC = 1
    ORT_ENABLE_EXTENDED = 2
    ORT_ENABLE_ALL = 99


class SessionOptions:
    def __init__(self):
        self.intra_op_num_threads = 0
        self.inter_op_num_threads = 0
        self.graph_optimization_level = GraphOptimizationLevel.ORT_ENABLE_ALL
        self.optimized_model_filepath = ""


def set_default_logger_severity(level):
    return None


class NodeArg:
    def __init__(self, name, shape=None, type_="tensor(float)"):
        self.name = name
        self.shape = shape
        self.type = type_


class InferenceSession:
    def __init__(self, path, sess_options=None, providers=None):
        threads = 0
        if sess_options is not None:
            threads = int(getattr(sess_options, "intra_op_num_threads", 0) or 0)
        self._h = _lib.ort_open(str(path).encode("utf-8"), threads)
        if not self._h:
            raise RuntimeError("onnxruntime shim: " + _last_error())
        buf = ctypes.create_string_buffer(256)
        if _lib.ort_input_name(self._h, buf, 256) != 0:
            raise RuntimeError("onnxruntime shim: " + _last_error())
        self._in_name = buf.value.decode("utf-8", "replace")
        n_out = _lib.ort_output_count(self._h)
        self._out_names = ["out%d" % i for i in range(max(0, n_out))]

    def get_inputs(self):
        return [NodeArg(self._in_name)]

    def get_outputs(self):
        return [NodeArg(n) for n in self._out_names]

    def run(self, output_names, input_feed):
        if not input_feed:
            raise RuntimeError("onnxruntime shim: empty input feed")
        arr = np.ascontiguousarray(next(iter(input_feed.values())), dtype=np.float32)
        shape = (ctypes.c_longlong * arr.ndim)(*arr.shape)
        out_ptr = ctypes.POINTER(ctypes.c_float)()
        out_shape = (ctypes.c_longlong * 16)()
        ndim = ctypes.c_int(0)
        count = ctypes.c_longlong(0)
        rc = _lib.ort_run_f32(
            self._h,
            arr.ctypes.data_as(ctypes.POINTER(ctypes.c_float)),
            shape,
            arr.ndim,
            ctypes.byref(out_ptr),
            out_shape,
            16,
            ctypes.byref(ndim),
            ctypes.byref(count),
        )
        if rc != 0:
            raise RuntimeError("onnxruntime shim: " + _last_error())
        try:
            flat = np.ctypeslib.as_array(out_ptr, shape=(int(count.value),)).copy()
            dims = tuple(int(out_shape[i]) for i in range(int(ndim.value)))
            return [flat.reshape(dims)]
        finally:
            if out_ptr:
                _lib.ort_free(out_ptr)

    def __del__(self):
        try:
            if getattr(self, "_h", None):
                _lib.ort_close(self._h)
                self._h = None
        except Exception:
            pass
