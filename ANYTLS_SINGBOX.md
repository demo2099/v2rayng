# AnyTLS + sing-box 集成文档

## 项目目标

将 v2rayNG 的核心从 xray-core 替换为 sing-box，以支持 AnyTLS 协议。

**原因**：xray-core 不支持 AnyTLS（Issues #4428 和 #5794 已关闭为 not_planned），而 sing-box v1.13.21 已支持。

---

## 项目结构

| 项目 | 路径 | 说明 |
|------|------|------|
| 原始 v2rayNG | `C:\Users\g\v2rayNG` | 上游仓库，只读参考 |
| Fork 仓库 | `C:\Users\g\v2rayNG_fork` | 开发工作目录，分支 `anytls-support` |
| GitHub Fork | `git@github.com:demo2099/v2rayng.git` | 远程仓库 |

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

---

## 当前问题：运行时 AnyTLS 连接不工作

### 问题描述
- APK 编译成功并安装
- 点击连接后 VPN 打不开
- 延迟测试（真实连接测试）显示 -1

### 延迟测试流程分析

`RealPingWorkerService.startRealPing()` 的流程：

1. **TCP socket 测试**（`SpeedtestManager.socketConnectTime`）
   - 直连 `server:port`，1秒超时
   - 如果 TCP 失败 → 直接返回 -1

2. **生成配置**（`CoreConfigManager.getV2rayConfig4Speedtest`）
   - 调用 `SingBoxConfigManager.getSingBoxConfig(guid, vpnMode=false)`
   - 生成配置但 **不启动 sing-box**

3. **HTTP 延迟测试**（`measureHttpDelay`）
   - **直接 HTTP 连接**，不经过代理
   - 这不是真正的代理测试

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
- `server_name` 只在 TLS 设置中，未在顶层设置
- sing-box proother 构建版本可能不支持 AnyTLS

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

### 修改文件
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
