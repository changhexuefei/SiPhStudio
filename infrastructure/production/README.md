# SiPhStudio Production Infrastructure

This directory describes the external services required by Phase 4.6. The application never treats a configured URL as proof that a service or device has passed production acceptance.

## 1. PostgreSQL multi-workstation coordination

Use a PostgreSQL multi-host JDBC URL for an HA cluster managed by Patroni, repmgr, a cloud PostgreSQL service, or another supported failover manager:

```text
-Dsiph.production.postgres.url=jdbc:postgresql://db-a:5432,db-b:5432/siphstudio?targetServerType=primary&hostRecheckSeconds=5&connectTimeout=10&socketTimeout=30
-Dsiph.production.postgres.user=siphstudio_app
-Dsiph.production.postgres.password=<secret>
-Dsiph.production.postgres.maximumPoolSize=16
-Dsiph.production.postgres.minimumIdle=2
-Dsiph.production.postgres.connectionTimeoutMs=10000
-Dsiph.production.postgres.validationTimeoutMs=5000
-Dsiph.production.postgres.leakDetectionThresholdMs=60000
```

The application uses HikariCP. Production coordination requires a writable primary. The health checker reports primary/standby role, read-only state, server version and standby replay lag.

HA responsibilities outside the desktop process:

- automatic leader election and primary promotion;
- synchronous/asynchronous replication policy;
- split-brain prevention;
- DNS, VIP or multi-host routing;
- WAL archive retention;
- monitoring and alerting;
- credential rotation;
- restore drills.

The GitHub Actions workflow starts a real PostgreSQL service and runs multi-worker reservation and Outbox concurrency tests. This proves PostgreSQL transaction and lock behavior for the tested build, but not a customer HA topology.

## 2. Backup and restore

`PostgresBackupService` invokes the installed PostgreSQL client tools:

- `pg_dump --format=custom`;
- `pg_restore --list` verification;
- SHA-256 manifest generation;
- explicit destructive acknowledgement before restore.

A production schedule should include:

- daily logical backups;
- continuous WAL archiving or managed point-in-time recovery;
- encrypted off-host storage;
- immutable retention;
- periodic restore into an isolated database;
- measured RPO and RTO.

The backup URI is a PostgreSQL URI, for example:

```text
postgresql://backup_user@db-vip:5432/siphstudio
```

Passwords are supplied through `PGPASSWORD` to the child process and are not written to the backup manifest.

## 3. MES integration

Configuration:

```text
-Dsiph.production.mes.baseUrl=https://mes.example.com
-Dsiph.production.mes.mappingFile=/etc/siphstudio/mes-mapping.json
-Dsiph.production.mes.bearerToken=<secret>
```

Start from `config/production/mes-mapping.example.json`. The mapping profile is versioned and defines:

- accepted event types;
- endpoint path;
- target fields;
- values sourced from Outbox metadata, payload paths or static values;
- static headers;
- accepted HTTP status codes.

Every request contains an `Idempotency-Key`. Failed delivery remains in the PostgreSQL Outbox and is retried with backoff. Repeated failure becomes `DeadLetter` and requires an audited operational decision.

A customer MES contract is not complete until its schema, authentication, error codes, Lot/Wafer/Site/Bin mapping and retry semantics have been approved by both sides.

## 4. Remote append-only audit service

Configuration:

```text
-Dsiph.production.audit.endpoint=https://audit.example.com/api/v1/audit/events
-Dsiph.production.audit.bearerToken=<secret>
```

The repository includes:

- `HttpRemoteAuditSink` for a remote service;
- `FileSystemWormAuditArchive` for immutable event files;
- `JvmWormAuditHttpServer` as a deployable reference service.

The reference service:

- accepts only append operations;
- validates the audit hash chain;
- creates one new file per event with `CREATE_NEW` and `DSYNC`;
- rejects mutation of an existing event;
- rejects a broken `previousHash` chain;
- returns an audit receipt header.

For regulated production, deploy it on a separate host and add filesystem/object-lock controls, restricted administrator access, trusted time, backup and retention policy. The in-repository service is an implementation reference, not a legal compliance certificate.

## 5. Enterprise identity

Supported JVM adapters:

### Keycloak / generic OIDC token introspection

```text
-Dsiph.production.identity.provider=keycloak
-Dsiph.production.identity.oidc.introspectionEndpoint=https://keycloak.example.com/realms/siph/protocol/openid-connect/token/introspect
-Dsiph.production.identity.oidc.clientId=siphstudio
-Dsiph.production.identity.oidc.clientSecret=<secret>
-Dsiph.production.identity.roleMappings=siph-operator=Operator,siph-engineer=Engineer,siph-quality=QualityEngineer,siph-supervisor=Supervisor,siph-auditor=Auditor
```

The desktop validates bearer tokens by introspection. Password grant is intentionally not implemented; use the enterprise browser/device login flow.

### LDAP / Active Directory bind

```text
-Dsiph.production.identity.provider=ad
-Dsiph.production.identity.ldap.url=ldaps://ad.example.com:636
-Dsiph.production.identity.ldap.userDnTemplate={username}@example.com
-Dsiph.production.identity.ldap.groupSearchBase=OU=Groups,DC=example,DC=com
-Dsiph.production.identity.ldap.groupSearchFilter=(&(objectClass=group)(member={userDn}))
-Dsiph.production.identity.ldap.groupNameAttribute=cn
-Dsiph.production.identity.roleMappings=SiPh Operators=Operator,SiPh Engineers=Engineer,SiPh Quality=QualityEngineer,SiPh Supervisors=Supervisor,SiPh Auditors=Auditor
```

Use TLS and an enterprise certificate chain. Group-to-role mapping must be reviewed by IT and Quality.

## 6. Real worker capability registration

Configuration:

```text
-Dsiph.production.worker.capabilityManifest=/etc/siphstudio/worker-capability-manifest.json
```

Start from `config/production/worker-capability-manifest.example.json`.

Real mode registers only evidence whose state is `ProductionQualified` and whose validity has not expired. The manifest requires separate issuer and approver identities, an approved safety profile and a valid Calibration Wafer qualification. Missing or invalid evidence prevents worker registration.

## 7. Production acceptance

Acceptance reports distinguish these kinds:

- digital infrastructure;
- PostgreSQL concurrency;
- MES contract;
- enterprise identity;
- remote audit;
- hardware soak;
- full production.

A report records completed tasks, success rate, duplicate results, throughput, p95 task latency, continuous duration, evidence references and known limitations.

Digital CI acceptance must always state that it did not exercise optical, electrical, motion, thermal or wafer hardware.

Full production acceptance still requires the real line:

- verified devices and fixtures;
- approved Calibration Wafer;
- operator identities;
- MES and audit servers;
- measured alignment success and repeatability;
- electrical/optical accuracy;
- UPH and continuous-run duration;
- recovery from disconnect, timeout, emergency stop and process restart;
- GR&R and Quality approval.
