# 独立启动 PI 虚拟 GCS（仅本机，只读）

本模拟服务是 SiPhStudio 自研的**测试工具**，不是 PI 官方仿真器，也不证明真实六轴设备兼容或运动安全。

## 启动

在仓库根目录运行（JDK 21）：

```powershell
.\gradlew.bat :pi-gcs-kotlin:runVirtualPiGcs
```

默认监听环回地址的 TCP 50001 端口。检查控制台输出的**实际** Host/IP 地址；在部分系统上会是 IPv6 `::1`，不要默认填写 `127.0.0.1`。需要其他端口，可用 Gradle JavaExec 的 `--args` 方式通过项目任务参数配置，或者由测试直接使用 `VirtualPiGcsTcpServer(port = 0)` 申请随机空闲端口。

在第二个终端启动桌面程序：

```powershell
.\gradlew.bat :composeApp:run
```

切换至 Physical，仅为验证既有真实 TCP 客户端的**只读**通路，填写模拟服务控制台打印的 Host 与 Port，点击 Connect → Refresh → Disconnect。

- `*IDN?` 返回 `VIRTUAL,PI-HEXAPOD,LOOPBACK-ONLY,NOT-HARDWARE`；
- `POS? X Y Z U V W` 返回当前虚拟六轴坐标。默认都是 0；
- `ERR?` 返回 0；
- 其他任何指令，包括 `MOV`、`MVR`、`SVO`、`STP`，都会被拒绝/断开，**不能**据此认为真实设备已停止；
- 服务只绑定系统环回地址，不对局域网开放，按 Ctrl+C 结束。

这里的 Physical 仅表示桌面 UI 使用了 TCP 真实通信路径，**其目标仍然是虚拟测试服务**，绝不能把连接成功标识当作真实硬件安全许可。遇到任何 PI 真实运动或硬件安全相关事项，需要另做现场验收。

## 测试与安全

运行 `./gradlew :pi-gcs-kotlin:test` 可以在无需独立运行服务时验证虚拟 GCS TCP 的身份、单位映射与拒绝写指令测试；真实 PI 设备现场测试仍为 NOT RUN。
