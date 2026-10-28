# 跨 App 支付跳转（微信 / 支付宝）：只拉起 App

> 定位：MCP 下单（麦当劳、美团、外卖…）拿到支付链接后，把用户**送到**微信 / 支付宝，剩下的交给用户在
> App 里完成。**不保证**落地在付款页，**不校验**支付结果，**不代操作**支付界面。

## 1. 它能做什么

| 输入 | 实际动作 |
|---|---|
| `scr open alipays://platformapi/startapp?...` | 启动支付宝（可能停在首页、扫一扫或付款页） |
| `scr open "https://qr.alipay.com/<code>"` | 改写成 `alipays://platformapi/startapp?appId=20000067&url=<encoded>` 后启动支付宝 |
| `scr open "weixin://wxpay/bizpayurl?pr=..."` | 启动微信（是否进付款页由微信决定） |
| `scr open "intent://...#Intent;scheme=weixin;package=com.tencent.mm;end"` | 按 `Intent.parseUri(URI_INTENT_SCHEME)` 解析后启动 |

能力边界：

- **只保证「App 被启动了」**：不同机型/版本、链接过期、用户没登录，都可能表现为「停在首页」甚至
  「弹一句提示」，这不是失败。解析不到 handler 也会真启动一次，失败才 404（Android 11+ 包可见性会让
  `resolveActivity()` 对用户后装的微信/支付宝返回 null）。
- 默认等前台变化 2000ms（微信/支付宝冷启动通常 2–4s，必要时传 `waitMs`）。

## 2. 推荐调用序列（Agent 侧）

```bash
# 1) 下单：MCP 返回支付链接
# 2) 只需拉起 App，不追求落到付款页
scr open "alipays://platformapi/startapp?appId=20000067&url=https%3A%2F%2Fqr.alipay.com%2Fxxx" 4000
#   或
scr open "weixin://wxpay/bizpayurl?pr=xxxxxx" 4000

# 3) 等用户自己付完并切回来（超时就走未支付分支）
scr wait-app com.eg.android.AlipayGphone --gone 60
#   或
scr wait-app com.tencent.mm --gone 60

# 4) 回来了查订单状态：以 MCP / 服务端为准
```

要点：

- **不要试图进入支付页**：不要 `scr find 立即支付`、不要点按钮、不要滚动找付款入口——支付 App 的界面
  千差万别，Agent 只会把用户带偏。
- **不要循环截图**判断「付没付」，也不要重复 `scr open`（会开出第二个支付页，`/open` 响应的
  `do_not:["screenshot","poll","retry"]` 就是这个意思）；支付结果**只以订单状态为准**。用户取消/超时，
  `wait-app --gone` 返回 `changed=false`，直接走「未支付 → 取消订单」分支。

## 3. 兼容性与安全边界

- 不需要无障碍：`startActivity` 是普通 API；`wait-app` 读前台包名时才需要无障碍（读不到时返回
  `503 a11y_unavailable`，改用固定等待 + 后续订单查询即可）。
- `weixin://` 由微信注册，系统直接启动；若装了多个自称处理该 scheme 的 App，可显式传
  `"package":"com.tencent.mm"`。支付宝冷启动慢导致 `changed=false` 属正常，把 `waitMs` 提到 4000
  或改用 `wait-app`。
- App 只负责「把用户送到支付 App」，**从不代点支付按钮**、不保存支付凭证、不读支付密码、不播报金额；
  跳转只用系统 `startActivity`，不需要任何支付相关权限。

## 4. 相关文件

- `app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt`（`/open`、`/wait-app`、`paymentSchemeFor()`）、
  `app/src/main/AndroidManifest.xml`（`<queries>` 支付 App 可见性）、`app/src/main/assets/gates/scr`
  （`open` / `wait-app`）、`app/src/main/assets/agents/DSH-MOBILE.md`（Agent 侧规则）
