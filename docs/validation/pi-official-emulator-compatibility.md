# PI 官方仿真器与 SiPhStudio 兼容性准备（尚未执行）

本说明是 **联调准备记录**，不是仿真器已通过兼容性测试的声明。现阶段没有真实 PI 六轴设备，也没有在本仓库的 CI 中实际运行 PI 官方仿真器。

## 1. 选择与官方资料

- **PIHexapodEmulator**：PI 官方说明其可模拟 C-887 六足位移台控制器、连接的六足位移台及两根附加轴，支持通过 TCP/IP 接收 GCS 指令进行用户程序验证。
- **PIControllerEmulatorGCS3.0**：针对 GCS 3.0 输入行为的虚拟控制器，通过 TCP/IP 通信，不应当成 C-887/GCS 2.x 的等价替代。
- PI 官方说明运行仿真器需要 QEMU，相关文件包含在 PI Software Suite 安装包中。
- 官方模拟与仿真页面：https://www.pi-china.cn/zh_cn/products/software-suite/simulation-emulation
- 官方 PIHexapodEmulator 手册名称：`C887T0001`。安装、VM 网络配置、TCP 端口和登录要求以与下载版本相匹配的官方手册为准。

**注意：** 当前应用默认 TCP 端口为 `50000`，这是应用的配置默认值，**不是**这里验证过的 PI 官方仿真器端口。不得根据上述资料直接假设模拟器监听端口、轴命名、GCS 固件版本或运动安全语义。

## 2. SiPhStudio 接入路径

```text
Desktop Physical UI (手动 Host / Port, Connect)
  → PiPhysicalTcpConnectionFactory
  → PiPhysicalConnectionLifecycle
  → PiGcsHexapodPort
  → GcsDevice / GcsClient
  → KtorTcpGcsTransport
  → PIHexapodEmulator TCP/IP（外部安装，不随项目发布）
```

主工程目前已拥有显式连接、`*IDN?` 身份查询、`POS?` 六轴查询、Disconnect，以及异步旧结果失效机制。请优先通过 **只读查询** 验证端到端链路，而不是先测试 MOV / MVR / SVO / STP。

## 3. 联调条件

1. 在隔离测试电脑上按 PI 官方许可及文档安装 PI Software Suite 与适用的官方模拟器，确认 QEMU 与虚拟网络正常。
2. 记录仿真器名称、版本、GCS 版本、模拟控制器型号、实际监听 Host、端口、客户端支持协议与轴名称。
3. 从仿真器自身或 PI 原生工具核实服务可用，避免误将本地网络端口测试成功当作 GCS 指令正确。
4. 在 SiPhStudio 选择 Physical，输入已确认的模拟器 Host / Port，执行 Connect → `*IDN?` → `POS?` → Refresh → Disconnect。
5. 分别记录实际收到的逐行/单行返回文本，验证六轴映射、线性单位 mm→µm 的转换假设，以及断开后旧坐标清除。若实际协议格式或单位不同，记录差异并另建修复 PR。
6. 故障场景优先用自研 Fake GCS 传输或隔离 TCP 测试服务进行，不要通过改变真实机械设备状态制造故障。

## 4. 兼容矩阵（初始全部 NOT RUN）

| 场景 | PIHexapodEmulator C-887 | PIControllerEmulatorGCS3.0 |
| --- | --- | --- |
| 安装与启动 | NOT RUN | NOT RUN |
| 确认 TCP Host/Port | NOT RUN | NOT RUN |
| `*IDN?` | NOT RUN | NOT RUN |
| `POS?` 六轴响应格式 | NOT RUN | NOT RUN |
| X/Y/Z 位置单位 | NOT RUN | NOT RUN |
| U/V/W 角度单位 | NOT RUN | NOT RUN |
| 连续刷新、断开、重新连接 | NOT RUN | NOT RUN |
| 异常响应、超时 | NOT RUN | NOT RUN |
| 真实硬件兼容性 | 不适用 | 不适用 |

### 证据规范

记录 Git commit、操作系统/JDK、模拟器版本、PI 软件版本、TCP 端点（可脱敏）、GCS 查询文本、响应文本（必要时脱敏）、UI 六轴截图、PASS/FAIL/SKIP 及复核者。失败时不要把已有离线 FakeTransport PASS 代替官方仿真器 PASS。

## 5. 验收门槛

- **阶段 A：** Kotlin/JVM CI 的协议解析、会话生命周期和模拟传输测试通过。
- **阶段 B：** 独立安装的官方 PIHexapodEmulator 完成只读真实 TCP 联调，并保留证据。
- **阶段 C：** 日后拿到真实硬件，按 [现场只读验收模板](./pi-physical-readonly-acceptance.md) 执行独立验收。
- **阶段 D：** 若考虑开放物理 Move/Jog/Stop，需再进行控制器型号、限位、碰撞与安全互锁的现场专项验证，不能从 A/B/C 自动推定完成。

**当前状态：阶段 B/C/D 均 NOT RUN。**
