# SiphStudio

This is a Kotlin Multiplatform project targeting Desktop (JVM), Web.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./composeApp/src/commonMain/kotlin) is for code that's common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple's CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, [jvmMain](./composeApp/src/jvmMain/kotlin)
      is the appropriate location.

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE's toolbar or run it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

### Optional PostgreSQL Multi-Workstation Production Coordination

The desktop application keeps the local production repository by default. Distributed worker leases, capability
matching, fencing tokens and MES/audit Outbox coordination can be moved to PostgreSQL by supplying JVM system
properties:

```text
-Dsiph.production.postgres.url=jdbc:postgresql://127.0.0.1:5432/siphstudio
-Dsiph.production.postgres.user=siphstudio
-Dsiph.production.postgres.password=<secret>
```

Operational rules:

- Demo mode without a PostgreSQL URL uses an in-memory digital coordinator.
- Real mode without a PostgreSQL URL reports the distributed coordinator as not configured.
- Real production measurement remains blocked until verified device executors and worker capabilities are injected.
- PostgreSQL coordination uses worker heartbeats, capability matching, expiring leases, fencing tokens,
  `FOR UPDATE SKIP LOCKED`, idempotent task keys and an Outbox for MES and remote audit delivery.
- H2 PostgreSQL-compatibility tests validate schema, transactions, sequential multi-worker reservation, fencing and
  Outbox behavior. H2 is not treated as proof of PostgreSQL's concurrent lock semantics; a real PostgreSQL integration
  environment is still required before production acceptance.

### Build and Run Web Application

To build and run the development version of the web app, use the run configuration from the run widget
in your IDE's toolbar or run it directly from the terminal:
- for the Wasm target (faster, modern browsers):
  - on macOS/Linux
    ```shell
    ./gradlew :composeApp:wasmJsBrowserDevelopmentRun
    ```
  - on Windows
    ```shell
    .\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun
    ```
We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).

---

第一阶段：不增加新硬件也能完成
SiPhWorkflowRunner 状态机；
校准模型和持久化；
测量位置训练；
光学验证与回位重复性测试；
Wafer/Die/Sub-Die 数据模型；
漂移检测策略；
测量结果完整追溯；
自动恢复和重试策略。
第二阶段：接入已有设备
真实光功率计适配器；
探针台适配器；
WaferMap/Die 走位；
激光器控制；
温控器联动；
O-O 自动测量。
第三阶段：需要新增视觉或传感器
相机采集接口；
Fiber tip 检测；
Grating/facet 检测；
视觉预对准；
Z displacement sensor；
Probe height training；
自动 Pivot 校准；
多温度自动复校。
第四阶段：生产化
Fiber Array 支持；
O-E、O-E-O 测试；
Calibration Wafer；
Lot 级任务调度；
质量趋势和 SPC；
自动异常分类；
可审计权限和操作记录。

Learn more about [Compose Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform.html).
