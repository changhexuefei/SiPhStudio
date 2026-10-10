# SiPhStudio

SiPhStudio 是面向硅光（Silicon Photonics）器件对准与测试的 Kotlin / Compose Multiplatform 上位机项目。当前桌面端提供 **Demo**（演示）与 **Physical**（PI 真实控制器）两种严格隔离的运行模式。PI 六轴 GCS 驱动位于 `pi-gcs-kotlin` 模块，桌面 UI 位于 `composeApp`。

> **安全说明**：此项目仍处于真实硬件联调阶段。CI 的模拟通信测试**不是**设备运动、限位、碰撞、急停或断电恢复的现场验收。初次联调应在操作员监护、机械行程和急停装置确认有效的条件下进行。

## 环境与启动

- JDK **21**（Gradle JVM 和桌面运行时保持一致）
- Windows / macOS / Linux 桌面环境；具体原生依赖需与运行系统匹配
- PI 控制器的网络可达性及 GCS TCP 服务（**仅 Physical 模式**需要）

Windows PowerShell：

```powershell
.\gradlew.bat :composeApp:run
.\gradlew.bat :composeApp:jvmTest :pi-gcs-kotlin:test
```

macOS / Linux：

```bash
./gradlew :composeApp:run
./gradlew :composeApp:jvmTest :pi-gcs-kotlin:test
```

> 项目同时配置 Kotlin/Wasm 浏览器目标；真实 PI GCS TCP 控制器接入位于 JVM 桌面端，不应将 Web/Demo 功能理解为物理设备控制能力。

## 运行模式

| 功能 | Demo | Physical (当前实现) |
| --- | --- | --- |
| 无设备运行演示 | 支持 | 不使用模拟数据冒充设备 |
| PI GCS TCP/IP Connect / Disconnect | 不使用 | 操作员主动触发 |
| 控制器身份 `*IDN?` | 演示数据 | 真实只读查询 |
| 六轴实际位置 `POS?` | 演示数据 | 真实只读查询 |
| X/Y/Z 显示单位 | µm | µm（驱动负责转换） |
| U/V/W 显示单位 | ° | ° |
| Move / Jog / Stop / 自动耦合 | 模拟 UI 操作 | **尚未通过 UI 开放真实运动控制** |

### Physical TCP 连接与读取

1. 启动桌面端，切换到 **Physical (not connected)**。未连接设备时，界面不允许把 Demo 的 Ready 状态作为真实运动许可。
2. 填入 PI 控制器 **Host/IP** 和 **Port**。默认端口是 `50000`，**必须以实际 PI 控制器型号和网络配置为准**；确认网络隔离与访问授权。
3. 点击 **Connect**：应用构造独立 GCS TCP 通信对象并显式建立连接。连接后通过真实设备会话查询身份与六轴坐标；不会自动使能伺服、回零或运动。
4. 点击 **Refresh physical readback** 主动刷新六轴位置。读数失败时清除旧的设备身份与坐标并报告错误，**不表示物理设备一定已断开或停止**。
5. 点击 **Disconnect** 断开。只有成功完成物理会话释放后，才允许清理所拥有的连接状态；断开失败时应检查通信与设备状态，再重试。连接期间限制切换回 Demo。
6. 关闭窗口时，应用尝试释放持有的物理会话；若断开失败，正常退出流程会受阻并显示错误，不应通过强制结束进程代替安全处置。

**联调须知**：第一次上电与连接前检查实际机械位置、限位、设备允许的坐标系和行程范围，确认急停/互锁和人员安全。不要根据界面上的读数成功来推断运动安全状态；真实运动功能的安全验收需要独立完成。

## 架构与安全边界

```text
composeApp/commonMain    Compose UI / Demo 与 Physical 模式隔离
composeApp/jvmMain       TCP 连接配置和工厂、Physical 会话生命周期
                         只读身份/位置快照、异步版本失效与 UI 状态映射
pi-gcs-kotlin            GCS 客户端、TCP 传输、六轴单位转换和运动安全状态
```

- `PiPhysicalConnectionLifecycle` 管理物理会话的连接所有权；断开失败时不会将仍受管理的物理端口当成正常释放。
- `PiPhysicalReadbackProjection` 将真实六轴快照映射到 UI；不能凭借查询成功推断 Ready。
- `PiPhysicalReadbackCoordinator` 通过版本失效避免较早请求覆盖新界面状态。
- `PiRuntimeMotionCommands` 包含独立的安全许可检查；当前 Physical UI 不开放 Move、Jog、Stop 等命令。
- 真实设备故障、位置异常、Stop、RecoveryRequired 和断电恢复的现场验证**尚未完成**。

## 测试与开发

运行桌面 JVM 单元测试与驱动测试：

```bash
./gradlew :composeApp:jvmTest :pi-gcs-kotlin:test
```

现有离线测试包含：连接成功/失败、重复连接、关闭失败后保留所有权、只读身份/六轴位置、会话快照一致性，以及异步旧结果丢弃。这些测试使用模拟 GCS 传输，不发送任何真实设备运动命令。

推荐的下阶段验收：使用明确受控的设备及已验证的网络地址，记录 Connect → IDN → POS → Disconnect、读数超时、断线重试、窗口关闭失败的证据；完成后再评估真实运动控制 UI。

### 真实 PI 六轴只读现场验收

在实际连接控制器前，请使用 [Physical 只读现场验收记录模板](./docs/validation/pi-physical-readonly-acceptance.md)。模板包含受控的 Connect → IDN → POS → Disconnect 步骤、故障测试约束、证据字段及明确的 NOT RUN 初始状态。此模板并不表示现场已通过验收。

## 项目状态

当前重点是 **桌面 Physical TCP 连接 + 六轴只读观测**，不是已验收的六轴自动寻光生产系统。有关问题和改进请使用仓库 [Issues](https://github.com/changhexuefei/SiPhStudio/issues) 与 [Pull Requests](https://github.com/changhexuefei/SiPhStudio/pulls)。
