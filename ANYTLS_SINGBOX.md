# AnyTLS + sing-box 集成文档

## 项目目标

将 v2rayNG 的核心从 xray-core 替换为 sing-box，以支持 AnyTLS 协议。

**原因**：xray-core 不支持 AnyTLS（Issues #4428 和 #5794 已关闭为 not_planned），而 sing-box v1.13.21 已支持。

---

## 项目结构

| 项目 | 路径 | 说明 |
|------|------|------|
| 原始 v2rayNG | `C:\Users\g\v2rayNG` | 上游仓库，只读参考 ⚠️ **已被污染，见下方说明** |
| Fork 仓库 | `C:\Users\g\v2rayNG_fork` | 开发工作目录，分支 `anytls-support` |
| GitHub Fork | `git@github.com:demo2099/v2rayng.git` | 远程仓库 |

> ⚠️ **`C:\Users\g\v2rayNG` 已不是干净上游**（2026-09-02 核查）：
> HEAD 多了提交 `13480eaa feat: add anytls protocol support with reality`，
> 工作区还有 11 个已修改文件（含 `CoreConfigManager.kt`、`RealPingWorkerService.kt`，`CoreNativeManager.kt` 已删）
> 和 3 个未跟踪的 `SingBox*.kt`。
> 要对照 pristine 上游请用 `git show HEAD:<path>`（HEAD 仍是干净的），
> 或重新 clone 一份干净的。混用两边容易改错目录。

---

## 关键配置信息

### sing-box 配置
- **版本**: sing-box v1.13.21
- **libbox.aar 来源**: `proother/sing-box-lib` releases（CI 时下载，不进 git）
- **GitHub Actions 工作流**: `.github/workflows/build-anytls.yml`

### AnyTLS URI 格式
```
anytls://uuid@server:port?security=reality&sni=...&fp=chrome&pbk=...&sid=...&type=tcp#Name
```

### 测试节点
```
anytls://...@45.128.210.149:443?security=reality&sni=www.intel.com&fp=chrome&pbk=v01dtbzQpa7qXAjUMFhU4R6BfmPsSr8MNJTI7fGAx0A&sid=b74e57077d619ecd&type=tcp#HKJ
```

### proother libbox.aar 限制
**PlatformInterface 只有 15 个方法**（singbox-android/libbox 有 28 个）：
```
autoDetectInterfaceControl, clearDNSCache, closeDefaultInterfaceMonitor,
findConnectionOwner, getInterfaces, includeAllNetworks, localDNSTransport,
openTun, readWIFIState, sendNotification, startDefaultInterfaceMonitor,
systemCertificates, underNetworkExtension, usePlatformAutoDetectInterfaceControl,
useProcFS
```

**CommandServerHandler 只有 5 个方法**：
```
getSystemProxyStatus, serviceReload, serviceStop, setSystemProxyEnabled, writeDebugMessage
```
（没有 `connectSSHAgent`, `triggerNativeCrash`）

---

## 已完成的修改

### 1. AnyTLS UI/解析/配置（10 项）

| 文件 | 修改内容 |
|------|----------|
| `enums/EConfigType.kt` | 添加 `ANYTLS` 枚举值 |
| `AppConfig.kt` | 添加 `ANYTLS = "anytls"` 常量 |
| `fmt/AnytlsFmt.kt` | 新建，URI 解析和序列化 |
| `handler/AngConfigManager.kt` | 导入菜单添加 AnyTLS |
| `ui/server/ServerAnytlsActivity.kt` | 新建，AnyTLS 服务器配置页面 |
| `ui/main/MainImportMenu.kt` | 导入菜单支持 AnyTLS |
| `RoutingManager.kt` | 路由规则支持 ANYTLS |
| `AndroidManifest.xml` | 注册 ServerAnytlsActivity |
| `strings.xml` | 添加 AnyTLS 相关字符串 |
| `core/CoreOutboundBuilder.kt` | 旧 xray outbound builder 保留引用 |

### 2. sing-box 核心集成（5 个核心文件）

| 文件 | 说明 |
|------|------|
| `core/SingBoxNativeManager.kt` | **新建** - sing-box CommandServer 封装，start/stop 生命周期 |
| `core/SingBoxPlatformInterface.kt` | **新建** - 实现 PlatformInterface（15 方法），`service.Builder()` 创建 VpnService.Builder |
| `core/SingBoxConfigManager.kt` | **新建** - sing-box JSON 配置生成，含 AnyTLS+REALITY |
| `core/CoreServiceManager.kt` | **重写** - 使用 SingBoxNativeManager，HttpURLConnection 强转 disconnect |
| `core/CoreConfigManager.kt` | **重写** - 委托给 SingBoxConfigManager |

### 3. 服务层修改

| 文件 | 修改 |
|------|------|
| `service/CoreVpnService.kt` | 重写，TUN 由 sing-box 通过 PlatformInterface.openTun 创建 |
| `service/CoreTestService.kt` | 使用 SingBoxNativeManager.initCoreEnv |
| `handler/CertificateFingerprintManager.kt` | 改用标准 Java SSL |

### 4. 依赖替换（5 个文件）
所有 `CoreNativeManager` → `SingBoxNativeManager` 的引用已更新

### 5. 删除文件
- `core/CoreNativeManager.kt` - 已删除（被 SingBoxNativeManager 替代）

### 6. 构建配置
- `app/build.gradle.kts` - packaging 添加 `go/Seq.class` 排除规则
- `.github/workflows/build-anytls.yml` - CI 时从 proother/sing-box-lib 下载 libbox.aar
- `.gitignore` - 排除 `libbox.aar`（不进 git）

### 7. 编译状态
**编译成功** - 经过 7+ 次 CI 迭代，所有 Kotlin 和 Java 编译错误已解决。

### 8. 运行时 Bug 修复（2026-09-02）

#### Bug #1（严重）：VPN 模式永远不激活
- **文件**：`CoreVpnService.kt:80`, `CoreServiceManager.kt:114`
- **问题**：`CoreVpnService.startService()` 调用 `CoreServiceManager.startCoreLoop(null)`，`vpnInterface` 始终为 null，导致 `vpnMode = vpnInterface != null` 永远为 false。sing-box 生成 mixed proxy 配置而非 TUN 配置，VPN 隧道从未建立。
- **修复**：`startCoreLoop()` 新增 `vpnMode` 参数，`CoreVpnService` 传入 `vpnMode = true`。

#### Bug #2（严重）：DNS 规则语法无效
- **文件**：`SingBoxConfigManager.kt:89-103`
- **问题**：DNS 规则使用 `rule_set` 配合 `geosite:cn`/`geoip:cn` 直接引用，sing-box 要求 `rule_set` 必须先在 `route.rule_set` 数组中定义 tag。
- **修复**：改用 `domain_suffix` 和 `ip_cidr` 规则。

---

## 当前问题：运行时 AnyTLS 连接不工作

### 问题描述
- APK 编译成功并安装
- 点击连接后 VPN 打不开
- 延迟测试（真实连接测试）显示 -1

### ✅ 根因已定位（2026-09-02）：-1 与 AnyTLS / REALITY 无关

**延迟测试链路对比（fork vs 上游 `13480eaa` 之前）**

| | 测速实现 | 是否经过节点 |
|---|---|---|
| 上游 | `CoreNativeManager.measureOutboundDelay(configJson, url)`（libv2ray JNI，真正起核心走代理） | ✅ |
| fork | `measureHttpDelay(url)`：`java.net.URL(url).openConnection()` 裸直连 | ❌ |

fork 删掉 `libv2ray` 后，libbox **没有** `measureOutboundDelay` 的等价 API
（已确认 `libbox.so` 中 `measureOutboundDelay` / `measureDelay` 符号数均为 0），
于是被替换成了不带任何代理的 `HttpURLConnection`。

默认测速 URL = `https://www.gstatic.com/generate_204`（`AppConfig.DELAY_TEST_URL`）。
国内网络直连该地址必然超时 → 抛异常 → `return -1L`。

> **结论：看到的 -1 是"手机能否直连 gstatic"的结果，跟 AnyTLS 配得对不对、REALITY 通不通没有任何关系。**

同样的写法也存在于 `CoreServiceManager.measureV2rayDelay()`（连接后的延迟/出口 IP 检测），也是直连。

另外 `getV2rayConfig4Speedtest()` 只生成 JSON，**从不交给 sing-box 校验**，
所以 `configResult.status` 恒为 `true` —— 延迟测试根本没有验证过 AnyTLS 配置是否合法。

#### 30 秒自证
设置 → 延迟测试 URL 改成国内可达地址（如 `https://www.baidu.com`）后重测：

- 出数字 → 证实上述结论（但注意：这个数字仍是**直连延迟**，是假值）
- 仍然 -1 → 再看 tcping；tcping 也 -1，才轮到"服务器/网络不可达"

本机实测 `45.128.210.149:443`：TCP 105ms 连通、TLS 1.3 握手成功 → 服务器在线，**排除原因 A**。

#### ✅ 已实施的修复（2026-09-02）：方向 2

**为什么不用方向 1**：`Libbox.newStandaloneCommandClient()` 里的 `standalone` 只是
"每次 RPC 后关闭 gRPC 连接"，`CommandClient.urlTest(groupTag)` / `URLTest()` 是一个
**void 异步触发器**，作用对象是**已经在跑的**守护进程，自己不带配置。
用它做批量测速意味着要把每个节点临时塞进运行中的实例，路径更长、副作用更大。

**采用的方案**：为每个节点起一个短命的 sing-box 实例，只配这一个 outbound，
请求通过它的 `mixed` inbound 发出去。

```
RealPingWorkerService.startRealPing()
  └─ CoreConfigManager.measureOutboundDelay(context, guid, url)
       ├─ SingBoxNativeManager.findFreePort()            // Libbox.availablePort(20000)，失败则 ServerSocket(0)
       ├─ SingBoxConfigManager.getSpeedtestConfig(ctx, guid, port)
       │    // 只有这个节点的 outbound + 一个 127.0.0.1 mixed inbound，final=proxy
       └─ SingBoxNativeManager.measureOutboundDelay(configJson, url, port)
            ├─ Libbox.checkConfig(config)                // 真正校验配置，不再恒为 true
            ├─ Libbox.newCommandServer(handler, SingBoxNoopPlatformInterface())
            ├─ server.startOrReloadService(config, OverrideOptions())   // ⚠️ 不调 start()
            ├─ waitForLoopbackPort(port)                 // 等 mixed inbound 起来
            ├─ openConnection(Proxy(HTTP, 127.0.0.1, port)) → 计时
            └─ closeService() / close()
```

**三个关键点**

1. **绝不能对临时实例调 `CommandServer.start()`**
   `Start()` 会 `os.Remove(sockPath)` 后绑定 `<basePath>/command.sock`，
   那正是主 VPN 服务用的 socket，一调用就把主服务踢下去。
   `StartOrReloadService` 是普通方法，不依赖 gRPC listener，直接调即可。
2. **临时实例不能用 `SingBoxPlatformInterface`**
   那个实现需要一个活着的 `VpnService`。新增
   `core/SingBoxNoopPlatformInterface.kt`：`openTun()` 返回 -1、其余按空实现，
   `localDNSTransport()` 回落到系统 DNS 的 `lookup()`。
3. **运行中的配置必须常驻一个 loopback `mixed` inbound**
   原来只有非 VPN 模式才生成 `mixed-in`，VPN 模式下 `CoreServiceManager.measureV2rayDelay()`
   无处可连。现在 `buildInboundsConfig()` 无论如何都会加
   `127.0.0.1:<socksPort>` 的 mixed inbound（app 自身 `addDisallowedApplication` 绕过 TUN，
   所以从 app 内连 127.0.0.1 能到这个 listener）。

**避免端口竞态**：`delayTestLock` 串行化所有临时实例（它们共享进程级全局量），
端口由 `Libbox.availablePort()` 分配，测完立即 `closeService()`。

#### 附带确认（AnyTLS + REALITY 组合本身合法）
- sing-box `anytls` outbound 自 **1.12.0** 起有 `tls` 字段，TLS 段支持 `reality`
- 使用的 libbox（proother v1.13.21）**已编译进 anytls**（`.so` 中 855 处 anytls 符号），版本字符串 `1.13.21`
- 启动前可先用 `Libbox.checkConfig(config)` 做一次配置校验

---

### 延迟测试流程分析（2026-09-02 修复后）

`RealPingWorkerService.startRealPing()` 的流程：

1. **TCP socket 测试**（`SpeedtestManager.socketConnectTime`）
   - 直连 `server:port`，1秒超时
   - 如果 TCP 失败 → 直接返回 -1
   - 复杂协议 / Hysteria2 / WireGuard / h3 ALPN 跳过这一步

2. **真实延迟测试**（`CoreConfigManager.measureOutboundDelay`）
   - 起临时 sing-box 实例（只含本节点 outbound + loopback `mixed` inbound）
   - `openConnection(Proxy(HTTP, 127.0.0.1, port))` **走代理**发请求
   - 返回实际往返耗时；配置非法 / 端口没起来 / 响应码非 2xx-3xx → -1

> `getV2rayConfig4Speedtest()` 现已无调用点（死代码），真正的校验在
> `measureOutboundDelay` 里的 `Libbox.checkConfig(config)`。

### 可能的失败原因

#### A. TCP socket 测试失败（服务器不可达）
- 服务器 45.128.210.149:443 端口未开放
- 服务器未运行 AnyTLS 服务
- 网络防火墙阻断

#### B. sing-box 配置生成失败
`SingBoxConfigManager.buildAnytlsOutbound()` 生成的配置：
```json
{
    "type": "anytls",
    "tag": "anytls-out",
    "server": "45.128.210.149",
    "server_port": 443,
    "password": "uuid",
    "tls": {
        "enabled": true,
        "server_name": "www.intel.com",
        "utls": {
            "enabled": true,
            "fingerprint": "chrome"
        },
        "reality": {
            "enabled": true,
            "public_key": "...",
            "short_id": "..."
        }
    }
}
```

潜在问题：
- `server_name` 只在 TLS 设置中，未在顶层设置 —— 这是合法的，sing-box 的 `anytls`
  outbound 的 SNI 本来就写在 `tls.server_name`，顶层没有 `sni` 字段
- ~~sing-box proother 构建版本可能不支持 AnyTLS~~ —— **已排除**：`.so` 中 855 处
  anytls 符号，版本字符串 `1.13.21`，anytls outbound 自 1.12.0 起支持 `tls.reality`

#### C. sing-box 服务启动失败
`SingBoxNativeManager.startService()` 可能的失败点：
- `Libbox.setup()` 初始化失败
- `CommandServer` 创建失败
- `startOrReloadService()` 配置验证失败

#### D. VPN TUN 创建失败
`SingBoxPlatformInterface.openTune()` 可能的失败点：
- `VpnService.Builder` 实例化问题
- `excludeRoute` API 兼容性问题（需要 API 33+）

---

## 需要用户提供的信息

### 1. 查看日志
- 打开 v2rayNG → 设置 → 查看日志
- 或导出日志文件

### 2. 关键日志关键词
```
Failed to start
error
exception
sing-box
checkConfig
startService
VPN
TUN
```

### 3. 测试步骤
1. 安装 APK
2. 添加 AnyTLS 服务器（手动输入或导入 URI）
3. 点击连接按钮
4. 查看日志中的错误信息

---

## 调试计划

### 下一步排查
1. **获取日志** - 用户提供 logcat 或应用内日志
2. **检查配置生成** - 确认 sing-box 配置 JSON 是否正确
3. **检查 sing-box 启动** - 确认 CommandServer 是否成功启动
4. **检查 TUN 创建** - 确认 PlatformInterface.openTun 是否被调用
5. **检查服务器可达性** - 确认 AnyTLS 服务器是否在线

### 可能的修复方向
1. 如果配置生成失败 → 修复 SingBoxConfigManager
2. 如果 sing-box 启动失败 → 修复 SingBoxNativeManager
3. 如果 TUN 创建失败 → 修复 SingBoxPlatformInterface
4. 如果服务器不可达 → 确认服务器配置

---

## 文件变更清单

### 新建文件
```
app/src/main/java/com/v2ray/ang/core/SingBoxNativeManager.kt
app/src/main/java/com/v2ray/ang/core/SingBoxPlatformInterface.kt
app/src/main/java/com/v2ray/ang/core/SingBoxConfigManager.kt
app/src/main/java/com/v2ray/ang/core/SingBoxNoopPlatformInterface.kt   # 2026-09-02 新增：临时延迟测试实例用的空 PlatformInterface
app/src/main/java/com/v2ray/ang/fmt/AnytlsFmt.kt
app/src/main/java/com/v2ray/ang/ui/server/ServerAnytlsActivity.kt
.github/workflows/build-anytls.yml
```

### 重写文件
```
app/src/main/java/com/v2ray/ang/core/CoreServiceManager.kt
app/src/main/java/com/v2ray/ang/core/CoreConfigManager.kt
app/src/main/java/com/v2ray/ang/service/CoreVpnService.kt
```

### 修改文件（2026-09-02 延迟测试修复）
```
app/src/main/java/com/v2ray/ang/core/SingBoxNativeManager.kt     # 新增 measureOutboundDelay / findFreePort / 等延迟测试支撑逻辑
app/src/main/java/com/v2ray/ang/core/SingBoxConfigManager.kt     # 新增 getSpeedtestConfig + buildMixedInbound + 常驻 loopback mixed inbound
app/src/main/java/com/v2ray/ang/core/CoreConfigManager.kt        # 新增 measureOutboundDelay(context, guid, url) 一调用
app/src/main/java/com/v2ray/ang/core/CoreServiceManager.kt      # measureV2rayDelay 改走 loopback 代理
app/src/main/java/com/v2ray/ang/service/RealPingWorkerService.kt # startRealPing 改调真实测速，删除 measureHttpDelay 死代码
```

### 修改文件（历史）
```
app/src/main/java/com/v2ray/ang/enums/EConfigType.kt
app/src/main/java/com/v2ray/ang/AppConfig.kt
app/src/main/java/com/v2ray/ang/handler/AngConfigManager.kt
app/src/main/java/com/v2ray/ang/ui/main/MainImportMenu.kt
app/src/main/java/com/v2ray/ang/RoutingManager.kt
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/java/com/v2ray/ang/core/CoreOutboundBuilder.kt
app/src/main/java/com/v2ray/ang/handler/CertificateFingerprintManager.kt
app/src/main/java/com/v2ray/ang/service/CoreTestService.kt
app/build.gradle.kts
.gitignore
```

### 删除文件
```
app/src/main/java/com/v2ray/ang/core/CoreNativeManager.kt
```

---

## GitHub Actions 工作流

`.github/workflows/build-anytls.yml` 关键步骤：

1. 检出代码
2. 设置 JDK 17
3. **下载 libbox.aar**（从 proother/sing-box-lib releases）
4. 初始化子模块
5. 编译 HEV tun2socks
6. 构建 APK
7. 上传 artifact

---

## 技术细节

### PlatformInterface 实现注意事项

```kotlin
// VpnService.Builder 必须用 enclosing instance 语法创建
// 因为 Kotlin 编译器将 VpnService.Builder 视为非静态内部类
val builder = service.Builder()

// excludeRoute 需要 IpPrefix，不是 (InetAddress, int)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    builder.excludeRoute(IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
}
```

### sing-box 配置格式

sing-box 使用 JSON 配置，主要结构：
```json
{
    "log": { "level": "warn", "timestamp": true },
    "dns": { "servers": [...] },
    "inbounds": [{ "type": "tun", ... }],
    "outbounds": [{ "type": "anytls", ... }],
    "route": { "rules": [...] }
}
```

### CommandServer vs BoxService

proother 构建中 **BoxService 未暴露**，只能使用 CommandServer：
```kotlin
val server = CommandServer(handler, platform)
server.start()
server.startOrReloadService(config, OverrideOptions())
```
