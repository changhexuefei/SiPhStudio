# SiphStudio

This is a Kotlin Multiplatform project targeting Desktop (JVM), Web.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./composeApp/src/commonMain/kotlin) is for code that's common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple's CoreCrypto for the iOS part, [iosMain](./composeApp/src/iosMain/kotlin)
      would be the right place for such calls. Desktop-specific code belongs in
      [jvmMain](./composeApp/src/jvmMain/kotlin).

### Build and Run Desktop (JVM) Application

- macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

### PostgreSQL Multi-Workstation Production Coordination

The desktop keeps the local production repository by default. Distributed Worker leases, capability matching,
fencing tokens and MES/audit Outbox coordination can use PostgreSQL and HikariCP:

```text
-Dsiph.production.postgres.url=jdbc:postgresql://db-a:5432,db-b:5432/siphstudio?targetServerType=primary&hostRecheckSeconds=5
-Dsiph.production.postgres.user=siphstudio
-Dsiph.production.postgres.password=<secret>
-Dsiph.production.postgres.maximumPoolSize=16
-Dsiph.production.postgres.minimumIdle=2
```

Operational rules:

- Demo mode without a PostgreSQL URL uses an in-memory digital coordinator.
- Real mode without a PostgreSQL URL reports the distributed coordinator as not configured.
- Real production remains blocked until a verified executor and a Production-Qualified Worker manifest are supplied.
- PostgreSQL coordination uses heartbeats, capability matching, expiring leases, fencing tokens,
  `FOR UPDATE SKIP LOCKED`, idempotent task keys and an Outbox for MES and remote audit delivery.
- H2 compatibility tests provide fast schema and transaction regression coverage.
- GitHub Actions also starts a real PostgreSQL service and verifies concurrent Worker reservation, concurrent Outbox
  delivery, Hikari pooling, writable-primary health, `pg_dump`, SHA-256 manifests, `pg_restore --list` and restore into
  an isolated database.
- A customer HA failover, enterprise directory, MES contract and real hardware soak still require those external
  systems. The software includes dedicated acceptance runners and keeps these results separate from full production
  acceptance.

Enterprise deployment, MES mapping, WORM audit, LDAP/AD/Keycloak, backup/restore and Worker manifest details are in
[`infrastructure/production/README.md`](./infrastructure/production/README.md).

### Build and Run Web Application

- macOS/Linux
  ```shell
  ./gradlew :composeApp:wasmJsBrowserDevelopmentRun
  ```
- Windows
  ```shell
  .\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun
  ```

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
