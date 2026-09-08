# Hosted database verification

Verified on 6 September 2026 using the installed MySQL client and the application's Java SchemaInitializer.

| Item             | Verified result                                                               |
| ---------------- | ----------------------------------------------------------------------------- |
| Server           | Aiven MySQL 8.4.8                                                             |
| Database         | `defaultdb`                                                                   |
| TLS              | CA and hostname verification succeeded                                        |
| Connection query | `SELECT 1 + 2 AS three` returned `3`                                          |
| Java connection  | Connector/J and Hikari successfully connected with the supplied CA truststore |
| Schema           | Version 2, 14 tables                                                          |
| Catalog          | Six sample destinations                                                       |
| Administrator    | Not yet provisioned                                                           |

Use this server-side URL for the application:

```text
jdbc:mysql://mysql-mining0005-akshatmining0005.l.aivencloud.com:20223/defaultdb?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC
```

The public CA certificate is in `deploy/aiven-ca.pem`. Both Dockerfiles import it into the Java runtime truststore. The truststore integrity password `changeit` is a standard truststore password, not a database credential; the store contains only trusted public certificates. Docker image builds and Vercel execution remain unverified.

For a non-Docker Java runtime, import the supplied certificate into a dedicated truststore with `keytool`, then configure `javax.net.ssl.trustStore`, `javax.net.ssl.trustStoreType=PKCS12` and its integrity password. Keep `sslMode=VERIFY_IDENTITY`. Do not substitute a numeric IP into the production JDBC URL; hostname verification must remain enabled.

Set `DB_INITIALIZE=false` during normal operation. Database credentials belong in the hosting platform's private environment settings. Before deployment, rotate the database password previously shared in chat, provision a restricted application user, and finish initial administrator setup. No database or administrator password is stored in this source tree.

Do not run the integration test suite against this hosted database. It is the application's initialized database, not a disposable fixture database. The earlier 11-test MySQL suite used a separate local MySQL 8.0.46 instance; it does not constitute a full hosted MySQL 8.4.8 regression test.
