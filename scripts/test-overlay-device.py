#!/usr/bin/env python3
"""真实输入回归：需启动 Normal 模式引擎、连接无障碍、展开浮窗并安装 androidTest APK。

先用 android screen capture -a / screen resolve 确认坐标。
例：python3 scripts/test-overlay-device.py SERIAL --outside 180 310 --overlap 727 598
仅使用 shell 调试权限与应用 Bridge，不调用 su、不变更设备权限。
"""

import argparse
import json
import re
import subprocess
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("--outside", nargs=2, type=int, required=True)
    parser.add_argument("--overlap", nargs=2, type=int, required=True)
    args = parser.parse_args()

    def adb(*argv):
        return subprocess.check_output(["adb", "-s", args.serial, *argv], text=True).strip()

    def state():
        return adb("shell", "content", "query", "--uri", "content://com.termux.adb/state")

    port = adb("forward", "tcp:0", "tcp:3083")
    try:
        token_row = adb("shell", "content", "query", "--uri", "content://com.termux.adb/token")
        token = re.search(r"key=token, value=([^\r\n]+)", token_row)[1].strip()

        def api(method, path, body=None):
            request = urllib.request.Request(
                "http://127.0.0.1:" + port + path,
                data=None if body is None else json.dumps(body).encode(),
                headers={"X-DSH-Token": token, "Content-Type": "application/json"},
                method=method,
            )
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)

        status = api("GET", "/status")
        mode = status["engine"]["priv_mode"]
        uid = status["app"]["uid"]
        if mode != "normal" or int(uid) <= 0:
            raise RuntimeError("测试仅允许 Normal 模式和非 root 应用 UID")
        initial = state()
        assert "a11yConnected, value=true" in initial, "需要已连接的无障碍服务"
        assert "level=1" in initial and "hideRefs=0" in initial, "需先展开浮窗并结束其他避让"
        adb("shell", "am", "force-stop", "com.termux.test")
        adb("shell", "am", "start", "-n", "com.termux.test/app.dsh.mobile.OverlayGestureTargetActivity")
        time.sleep(1)

        def target_text():
            screen = api("GET", "/screen?format=json")
            assert screen["foreground"] == "com.termux.test", "触摸靶场不在前台"
            return next(node["text"] for node in screen["nodes"]
                        if node.get("text", "").startswith("Overlay gesture target"))

        assert "Touches: 0" in target_text()
        for index, point in enumerate([args.outside, args.overlap, args.overlap], start=1):
            start = time.monotonic()
            result = api("POST", "/gesture", {
                "type": "long_press", "x": point[0], "y": point[1], "durationMs": 4000,
            })
            assert result.get("ok"), result
            time.sleep(1.4)
            during = state()
            if index > 1:
                assert "hideRefs=1" in during, "长按 1200ms 后避让提前结束"
                assert 1.2 < time.monotonic() - start < 4, "采样未落在长按中间"
            else:
                assert "hideRefs=0" in during, "不相交操作不应隐藏浮窗"
            time.sleep(3)
            after = state()
            assert "hideRefs=0" in after and "level=1" in after, "浮窗未恢复"
            label = target_text()
            assert f"Touches: {index}\n" in label and "Held: 4000 ms" in label, label
            print(f"PASS: {'outside' if index == 1 else 'overlap'} long press {index}, delivered 4000ms", flush=True)
        print(json.dumps({"appUid": uid, "privMode": mode, "passed": 3}))
    finally:
        adb("forward", "--remove", "tcp:" + port)


if __name__ == "__main__":
    main()
