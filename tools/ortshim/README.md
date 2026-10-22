# libortshim.so — a tiny ONNX Runtime binding for Termux

Termux's `onnxruntime` package (1.31.0-3) ships **only** `libonnxruntime.so` + headers: its `.deb`
contains no `site-packages/onnxruntime`, so `import onnxruntime` fails regardless of Python version
(the python wheel built by `termux_step_make` is not part of the package revision currently in the
repo). That is why PP-OCRv6 reported `unavailable: ... no module named onnxruntime`.

`ortshim.c` wraps the official C API (`onnxruntime_c_api.h`) into the tiny surface PP-OCRv6 uses:

```
ort_open(path, threads) / ort_close
ort_input_name / ort_output_count
ort_run_f32(handle, float data, int64 shape, ndim) -> float output + shape
```

`assets/ppocr6/py/onnxruntime.py` is a ctypes module presenting `InferenceSession`,
`SessionOptions`, `GraphOptimizationLevel`, `set_default_logger_severity` so the unmodified ppocr6
script can `import onnxruntime`. `ppocr6` prepends that directory to `sys.path` (`PPOCR6_PY`).

## Rebuild (on a Termux device)

```bash
PREFIX=/data/data/com.termux/files/usr
$PREFIX/bin/clang -shared -fPIC -O2 \
  -o libortshim.so tools/ortshim/ortshim.c \
  -I$PREFIX/include/onnxruntime -L$PREFIX/lib -lonnxruntime \
  -Wl,-rpath,$PREFIX/lib
```

Copy `libortshim.so` next to `onnxruntime.py` (assets/ppocr6/py/) and rebuild the APK.

Verified on LineageOS/aarch64: ppocr6 tiny ran in ~0.98s (det 0.64s, rec 0.09s) on a 1080x2400
screenshot, returning text + bbox + center.
