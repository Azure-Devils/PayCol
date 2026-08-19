# ADR 003: TTL del almacén de transacciones, red y respaldo del almacén de casos (Semana 2)

**Fecha:** 2026-07-24
**Estado:** Aceptada (IaC lista, sin ejecutar)
**Autoridad:** el equipo de DevOps/Infra, sobre la base de `docs/weeks/Semana2-Azure.md` y confirmada por el patrón de austeridad ya establecido en el ADR 001.

## Contexto

La Semana 2 exige, entre otras cosas:

1. Una política de expiración automática (TTL) sobre el almacén de transacciones (Cosmos DB), justificada en función de las ventanas temporales que usan las cuatro reglas de detección.
2. Un almacén relacional de casos de fraude (PostgreSQL Flexible Server, ya decidido en el ADR 001 como rol *actual* de Postgres) que **no sea alcanzable desde internet**, con una estrategia de respaldo documentada (periodicidad, retención, RPO).

Ambas decisiones se documentan aquí porque comparten el mismo criterio rector: **el nivel gratuito/más barato que alcance para una demo académica, sin sacrificar el requisito funcional explícito** (TTL correcto, aislamiento de red real).

## Parte 1 — TTL del container `transactions`

### Ventanas temporales de las cuatro reglas (según `docs/weeks/Semana2-Azure.md`, sección 2.3)

| Regla | Ventana temporal que necesita |
|---|---|
| Velocidad | Corta (minutos) — cantidad de transacciones recientes de la cuenta. |
| Monto atípico | **La más larga de las cuatro** — "desviación del monto respecto al comportamiento histórico de la cuenta". Necesita semanas de historial para que el promedio/desviación sea representativo, no solo las últimas horas. |
| Geo-imposible | Muy corta — solo compara la transacción actual contra la inmediatamente anterior. |
| Comercio de riesgo | Ninguna — es una consulta contra una lista estática, no depende de historial temporal. |

El documento de la Semana 2 no fija un número exacto ("Justificar el periodo elegido en función de las ventanas temporales que utilizan las reglas"), así que se propone aquí un valor concreto.

### Decisión: `defaultTtl = 7,776,000 segundos (90 días)`

- **Por qué 90 días y no una ventana corta (p. ej. 7 días):** la regla de "monto atípico" es la que fija el límite inferior aceptable — necesita suficiente historial para que un promedio/desviación estándar del comportamiento de la cuenta sea confiable y no esté dominado por ruido de pocas transacciones. Un TTL de 7-14 días arriesgaría dejar cuentas de bajo movimiento (p. ej. una tarjeta que se usa cada 2-3 semanas) con muy poco historial disponible para el cálculo, produciendo falsos positivos/negativos.
- **Por qué 90 días y no un valor mucho mayor (p. ej. 1 año) o infinito:** este proyecto tiene una carga de **escritura constante de alto volumen** (según la sección 2.1 del documento de la semana). Sin TTL, el volumen crecería indefinidamente y competiría por el límite de **25 GB** del free tier permanente de Cosmos. 90 días da margen de sobra (3 meses de comportamiento histórico es un estándar razonable en la industria de detección de fraude para "baseline" de comportamiento) sin acumular datos que ya no aportan a ninguna regla activa.
- **Mecanismo:** `defaultTtl` a nivel de container (no por documento), de forma que **todo documento se borra automáticamente** 90 días después de su última modificación, sin job de limpieza ni intervención manual — así lo pide el requisito ("Configurar la eliminación automática").
- **Por qué NO se aplica TTL al container `customers`:** son entidades de referencia (identidad de la cuenta), no datos con ventana temporal — deben persistir mientras la cuenta exista.
- **Alternativas descartadas:**
  - TTL por documento en vez de `defaultTtl` de container: más flexible pero requiere que el código setee `ttl` en cada escritura; sin ganancia real para este caso de uso (todas las transacciones comparten la misma política).
  - TTL basado en un job/Function programada en vez del mecanismo nativo de Cosmos: agrega un componente serverless adicional (más superficie de costo/mantenimiento) para resolver algo que Cosmos ya hace gratis de forma nativa.

**Implementación:** `infra/cosmos-db.bicep`, propiedad `defaultTtl` en `containers/transactions`, parametrizada como `transactionsDefaultTtlSeconds` (default `7776000`) en `infra/main.bicep`.

**Pendiente de coordinación:** si el equipo de backend define las ventanas exactas de las reglas de velocidad/monto atípico con un número distinto (p. ej. baseline de 60 días en vez de 90), este valor se ajusta sin problema — es un parámetro, no un valor hardcodeado.

## Parte 2 — Aislamiento de red del almacén de casos (Postgres Flexible Server)

### Requisito

"Acceso restringido a la subred de aplicación... El almacén no debe ser alcanzable desde internet" (sección 2.2).

### Opciones evaluadas

| Opción | Recursos adicionales | Costo recurrente | Complejidad |
|---|---|---|---|
| **VNet integration nativa + subnet delegada + Private DNS Zone** (elegida) | VNet, 2 subredes, 1 Private DNS Zone, 1 link de zona | Private DNS Zone: **~0.50 USD/zona/mes** + ~0.40 USD/millón de consultas DNS (despreciable a este volumen). VNet/subredes: sin costo. | Media: 4 recursos de red antes de llegar al server, pero es el patrón "de fábrica" soportado directamente por la propiedad `network` del recurso Postgres — un solo recurso `flexibleServers` con dos propiedades (`delegatedSubnetResourceId`, `privateDnsZoneArmResourceId`). |
| Private Link / Private Endpoint | VNet, subred (sin delegación), 1 Private Endpoint, 1 NIC asociado, posible Private DNS Zone también | Private Endpoint: **~0.01 USD/hora ≈ 7-10 USD/mes**, más el costo de la Private DNS Zone igual que la opción anterior. | Mayor: el Private Endpoint es un recurso de red independiente que necesita su propia gestión de ciclo de vida, y sigue requiriendo la zona DNS privada de todas formas. |
| Firewall de IP restringido (acceso público con reglas de IP permitidas) | Ninguno adicional | 0 USD | Baja, pero **no cumple el requisito**: el servidor sigue teniendo un endpoint público alcanzable por TCP desde internet (solo el firewall decide qué IP puede intentar conectarse) — el enunciado exige "no alcanzable desde internet", no "con acceso restringido por IP". |

### Decisión

Se usa **VNet integration nativa con subred delegada + Private DNS Zone**, por ser la opción soportada nativamente por el recurso (menos piezas móviles) y estrictamente más barata que Private Link para el mismo resultado de aislamiento. Se descarta la opción de firewall por IP porque no satisface el requisito explícito de "no alcanzable desde internet" — un servidor con `publicNetworkAccess: Enabled` y reglas de firewall sigue siendo, técnicamente, un endpoint público.

**Costo real a confirmar con el usuario antes de desplegar:** la Private DNS Zone (~0.50 USD/mes) es el único componente de este diseño de red que genera un cargo fijo mensual, por pequeño que sea. No es "gratis" como el resto de la capa de red (VNet/subredes). Se documenta aquí explícitamente porque la regla de oro del proyecto exige mostrar cualquier costo real, sin importar cuán bajo sea, antes de aprovisionar.

**Implementación:** `infra/postgres-cases.bicep` (VNet `vnet-centinela`, subred delegada `snet-postgres` de tamaño mínimo `/28`, subred `snet-app` reservada sin delegar aún para el futuro recurso de cómputo del backend, Private DNS Zone `privatelink.postgres.database.azure.com`).

### Autenticación: AAD-only (sin contraseña)

Además del aislamiento de red, se aprovechó este módulo para eliminar también la última credencial estática pendiente del proyecto: el servidor se crea con `authConfig.passwordAuth = 'Disabled'` y `authConfig.activeDirectoryAuth = 'Enabled'` — cero `administratorLogin`/`administratorLoginPassword`. La Managed Identity del backend (cuando exista) y, opcionalmente, la cuenta AAD del usuario, se dan de alta como *administradores* del servidor vía el recurso `Microsoft.DBforPostgreSQL/flexibleServers/administrators`, autenticándose con un token de Microsoft Entra ID en vez de una contraseña.

- **Riesgo aceptado a corto plazo:** autenticar JDBC contra Postgres con un token AAD como password requiere un mecanismo de refresco de token en el lado de Spring Boot (p. ej. el starter `spring-cloud-azure-starter-jdbc-postgresql`, que hoy NO está en el `pom.xml` del proyecto). Esto es trabajo adicional de coordinación con el equipo de backend que no existía cuando Postgres se descartó originalmente en la Semana 1.
- **Fallback documentado:** `infra/postgres-cases.bicep` expone un parámetro `enablePasswordAuth` (default `false`). Si el equipo decide que cablear AAD-only en el tiempo restante del sprint es demasiado riesgoso, se puede activar en `true` y pasar `administratorLogin`/`administratorLoginPassword` como parámetros seguros en el momento del deploy real (nunca con valores por defecto, nunca committeados) — el valor resultante debe guardarse únicamente como el secreto `postgres-connection-string` en Key Vault (`infra/key-vault.bicep`), jamás en `application.properties` ni en el repo.

### Estrategia de respaldo

| Aspecto | Valor elegido | Justificación |
|---|---|---|
| **Periodicidad** | Automática, gestionada por el servicio (snapshots completos diarios + archivado continuo de WAL). No configurable de forma independiente en Flexible Server: viene incluida sin costo adicional mientras el respaldo total no exceda el storage provisionado (32 GB). | Es el mecanismo nativo de Postgres Flexible Server — no requiere un job/Function propio ni un `pg_dump` programado, que agregaría complejidad y superficie de fallo sin necesidad. |
| **Retención** | 7 días (`backupRetentionDays: 7`, parametrizable hasta 35). | 7 días es el mínimo/default y cae dentro del almacenamiento de respaldo incluido sin costo extra (hasta el 100% del storage provisionado). Dado el volumen bajo de datos de casos (sección 2.2 de la Semana 2: "volumen bajo, con relaciones entre entidades") y la duración total del proyecto (3 semanas), 7 días de ventana de restauración es razonable: cubre cualquier error operativo detectado dentro de la semana de trabajo en curso sin acercarse al límite de storage gratuito. Se deja como parámetro (`backupRetentionDays`) por si el usuario prefiere ampliarlo a 14-35 días — cada día adicional de retención consume más del storage de respaldo incluido, y solo generaría costo si se llegara a exceder ese 100% del storage provisionado (poco probable con 32 GB y datos de bajo volumen, pero no descartable si el volumen de casos crece más de lo esperado).
| **RPO (pérdida máxima tolerable de datos)** | Del orden de minutos (restauración a un punto en el tiempo/PITR gracias al archivado continuo de WAL, no solo al snapshot diario). | El servicio permite restaurar a cualquier punto dentro de la ventana de retención (no solo al último snapshot diario), gracias al log continuo de transacciones — esto da un RPO mucho más ajustado que "hasta 24 horas", sin configuración adicional ni costo extra. |
| **Geo-redundancia** | Deshabilitada (`geoRedundantBackup: 'Disabled'`). | Duplicaría el costo de almacenamiento de respaldo sin necesidad real para un proyecto de 3 semanas en una sola región — se documenta como decisión consciente, no como omisión. |

## Parte 3 — Auditoría de secretos en el historial de git (Key Vault, requisito 2.6)

Como parte de la migración a Key Vault (`infra/key-vault.bicep`), se auditó el historial completo de `src/main/resources/application.properties` en busca de credenciales commiteadas:

```
git log --all -p -- '**/application.properties' | grep -n -i "password"
```

**Hallazgo:** el commit `730a37b` ("build: bootstrap proyecto Spring Boot 3.3.4 / Java 21") introdujo la línea:

```
spring.datasource.password=centinela
```

sin comentar, es decir, activa en ese momento. El commit `433eb97` ("feat(persistence): migrar de PostgreSQL a Azure Cosmos DB") la comentó (`#spring.datasource.password=centinela`) al desactivar el perfil de Postgres, pero **el valor sigue existiendo en el historial de git** (`730a37b`) y también queda visible, comentado, en el archivo actual.

**Evaluación de riesgo:** el valor (`centinela`) es una contraseña de desarrollo local trivial, coincide con el nombre del proyecto/usuario de la base de datos local, y nunca correspondió a una instancia real de Azure (el Postgres real de casos, `infra/postgres-cases.bicep`, ni siquiera existía en ese momento). No representa una credencial de producción expuesta. Aun así, se documenta explícitamente porque:

- Es la práctica correcta independientemente de la severidad — el hábito de nunca dejar pasar una credencial en texto plano, real o de prueba, es lo que se está construyendo con la migración a Key Vault.
- Sienta el precedente para el equipo de backend: cualquier valor real de conexión (incluyendo el futuro connection string AAD-based de Postgres) debe ir exclusivamente vía variable de entorno inyectada desde Key Vault, nunca en `application.properties`, ni siquiera comentado "por si acaso".

**Acción recomendada (pendiente, coordinación con el equipo de backend):** eliminar por completo las líneas comentadas de `spring.datasource.*` de `application.properties` en un commit futuro, ya que el perfil `postgres-legacy` puede documentarse solo en el ADR/README sin necesidad de dejar el valor (aunque esté comentado) en el archivo versionado. Reescribir el historial de git (`git filter-repo`/BFG) para eliminar el commit `730a37b` original **no se recomienda** dado que el valor no es sensible y reescribir historia en una rama ya compartida (`develop`) tiene más riesgo (romper referencias de otros colaboradores) que beneficio en este caso — decisión a confirmar con el usuario si prefiere igualmente purgarlo.

## Consecuencias

- `infra/cosmos-db.bicep` y `infra/main.bicep` quedan con el parámetro `transactionsDefaultTtlSeconds` (default 90 días).
- `infra/postgres-cases.bicep` es un módulo nuevo con 6 tipos de recurso (VNet, 2 Private DNS resources, server, database, hasta 2 administrators) — el más complejo de los módulos de infra hasta ahora.
- Queda pendiente, antes de cualquier despliegue real: (a) confirmar con el usuario si la suscripción actual (trial estándar 200 USD/30 días) incluye el beneficio de "12 meses gratis" que cubriría el cómputo B1ms de Postgres — si NO aplica, el costo mensual esperado de este único recurso ronda los 12-13 USD, muy por encima de cualquier otro recurso del proyecto; (b) aprobar explícitamente el cargo fijo de la Private DNS Zone (~0.50 USD/mes); (c) decidir junto con el equipo de backend si se sigue con autenticación AAD-only o se activa el fallback de password.
- Ver también el re-chequeo de presupuesto en `infra/README.md` (sección "Re-chequeo de presupuesto — Semana 2").

## Referencias

- `docs/weeks/Semana2-Azure.md`, secciones 2.1, 2.2 y 2.6.
- Microsoft Learn — referencia ARM/Bicep de `Microsoft.DBforPostgreSQL/flexibleServers` y `flexibleServers/administrators` (API `2022-12-01`, GA), consultada el 2026-07-24 para confirmar el esquema exacto de `authConfig`, `network` y el recurso hijo `administrators`.
- `docs/decisions/001-migracion-postgresql-a-cosmosdb.md` — contexto de por qué Postgres pasó de almacén de transacciones a almacén de casos.
