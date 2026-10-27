# 跨 App 支付跳转（微信 / 支付宝）

> 目标：MCP 下单（麦当劳、美团、外卖…）拿到支付链接后，App 能像普通 App 一样把用户送到
> 微信 / 支付宝的付款页，付完再回到 DSH 会话，中间不截图刷屏、不重复下单。

## 1. 能用什么

| 输入 | 实际动作 | 谁需要无障碍 |
|---|---|---|
| `scr open alipays://platformapi/startapp?...` | 支付宝付款页 | 不需要（只跳转） |
| `scr open "https://qr.alipay.com/<code>"` | 自动改写成 `alipays://platformapi/startapp?appId=20000067&url=<encoded>` 再打开 | 不需要 |
| `scr open "weixin://wxpay/bizpayurl?pr=..."` | 微信支付页 | 不需要 |
| `scr open "weixin://dl/business/?t=..."` | 微信小程序 / 业务页 | 不需要 |
| `scr open "https://wx.tenpay.com/..."` | 浏览器打开（微信支付 H5 只能在微信内完成，网页会提示「打开微信」） | 不需要 |
| `scr open "intent://...#Intent;scheme=weixin;package=com.tencent.mm;end"` | 按 `Intent.parseUri(URI_INTENT_SCHEME)` 解析后打开 | 不需要 |

`POST /open`（门禁 `scr open` 背后）在 v2.5.7 起：

- 支持 `intent://` URI（网页/支付回调最常见的格式）；
- 支付宝的 `https://*.alipay.com` 收款链接自动改写为 `alipays://`；
- **解析不到 handler 也先真启动一次**，失败才回 404 —— 因为 Android 11+ 的包可见性
  会让 `resolveActivity()` 对「用户后装的微信/支付宝」返回 null，但那不代表打不开；
- 清单里已声明 `weixin` / `alipays` / `alipayqr` / `intent` / `https` 的包可见性；
- 默认等前台变化 2000ms（微信/支付宝冷启动通常 2–4s，必要时传 `waitMs`）。

## 2. 推荐调用序列（AI 侧）

```bash
# 1) 下单：MCP 返回支付链接/二维码内容
# 2) 跳到支付 App
scr open "alipays://platformapi/startapp?appId=20000067&url=https%3A%2F%2Fqr.alipay.com%2Fxxx" 4000
#   或微信
scr open "weixin://wxpay/bizpayurl?pr=xxxxxx" 4000

# 3) 等用户付完回来（用户在微信/支付宝里点「完成」，再切回 DSH）
scr wait-app com.eg.android.AlipayGphone --gone 60
#   或
scr wait-app com.tencent.mm --gone 60

# 4) 回来了再查订单状态（MCP 侧），不要自己截图猜「付没付」
```

要点：

- **不要循环截图**。回到前台之前截图只会拍到支付 App；用 `wait-app` 阻塞等待。
- **不要重复 `scr open`**。重复打开会生成第二个支付页，`/open` 的响应里带
  `do_not:["screenshot","poll","retry"]` 就是提醒这个。
- 支付结果以 MCP / 服务端订单状态为准，不以界面为准。
- 用户拒绝/取消支付时，`wait-app --gone` 会在超时后返回 `changed=false`，
  继续走「未支付 → 取消订单」的分支即可。

## 3. 兼容性

- 需要无障碍吗：**不需要**。跳转、等待前台变化（读 `dumpsys`/生命周期）都不依赖无障碍服务；
  只有「读支付页上的文字/控件」才需要（`scr find`、`scr tap-text`）。
- 微信 `weixin://` 首次跳转可能弹「是否打开微信」？不会——`weixin://` 是微信注册的
  scheme，系统直接启动；若手机上同时装了多个自称处理 `weixin://` 的 App，可显式传
  `"package":"com.tencent.mm"` 或 `"targetPackage":"com.tencent.mm"`。
- 支付宝冷启动慢时 `changed=false` 属于正常，`waitMs` 提到 4000 或改用 `wait-app`。
- 支付链接过期/无效时，App 会打开并显示「二维码已失效」——这不是跳转失败，看 `/open`
  返回的 `ok:true` 即可。

## 4. 安全边界

- App 只负责「把用户送到支付页」，**从不代点支付按钮**、不保存支付凭证、不读支付密码。
- 不需要用户给 App 任何支付相关权限；跳转用的都是系统 `startActivity`。
- 支付金额/收款方以支付 App 内的确认页为准，AI 侧不应自行播报金额。

## 5. 相关文件

- `app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt`：`/open`、`/wait-app`、`paymentSchemeFor()`
- `app/src/main/AndroidManifest.xml`：`<queries>` 支付 App 可见性
- `app/src/main/assets/gates/scr`：`open` / `wait-app` 子命令
