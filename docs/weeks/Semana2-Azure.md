# Centinela — Semana 2

## Motor de scoring y arquitectura orientada a eventos


## 1. Alcance de la semana

Esta semana se construye el núcleo funcional del sistema: los almacenes de datos, el motor de scoring serverless con las cuatro reglas de detección, y la capa de mensajería que desacopla la ingesta del análisis.

Al cierre de la semana, una transacción recibida por la API debe puntuarse automáticamente contra su historial y, si supera el umbral, generar un caso de fraude. Todo el proceso posterior a la ingesta ocurre sin intervención manual y sin que el cliente que originó la transacción espere por él.

**Restricción arquitectónica central de la semana:** la API responde al cliente antes de que el análisis concluya. Una implementación en la que la API invoque directamente al motor de scoring y espere su resultado no cumple el requisito, aunque produzca la salida esperada.

**Fuera del alcance de esta semana:** contenedores, despliegue automatizado, servicios de inteligencia artificial, explicador de casos, observabilidad instrumentada.


## 2. Requerimientos

### 2.1 Almacén de transacciones

Almacén no relacional destinado a las transacciones y sus scores. Perfil de carga: escritura constante de alto volumen y una consulta dominante — *obtener las transacciones recientes de una cuenta determinada*, ejecutada por el motor de scoring en cada transacción procesada.

**Requerimientos:**

- **Clave de partición.** Debe permitir que el motor recupere el historial de una cuenta sin recorrer particiones ajenas. La justificación escrita debe indicar qué consulta optimiza y cuál sacrifica.
- **Nivel de consistencia.** Seleccionar y justificar considerando el compromiso entre garantía de lectura y latencia. Documentar si el caso de uso requiere consistencia fuerte y por qué.
- **Política de expiración de datos.** Configurar la eliminación automática de registros que dejan de aportar al análisis. Justificar el periodo elegido en función de las ventanas temporales que utilizan las reglas.
- **Nivel de servicio.** Utilizar el nivel gratuito disponible. Documentar sus límites de capacidad y almacenamiento.

La clave de partición no admite modificación posterior sin migración completa de los datos. Debe definirse antes de la primera escritura.

### 2.2 Almacén de casos de fraude

Almacén relacional destinado a la gestión de casos. Perfil de carga: volumen bajo, con relaciones entre entidades, integridad referencial, reportería y trazabilidad.

**Modelo de datos mínimo:**

| Entidad | Contenido |
|---|---|
| Caso | Referencia a la transacción, score obtenido, estado actual, fecha de apertura |
| Estado | Catálogo de estados posibles del caso |
| Asignación | Relación caso–analista |
| Resolución | Decisión final, analista responsable, fecha, observaciones |
| Auditoría | Registro inmutable de cada cambio de estado: qué cambió, quién y cuándo |

**Requerimientos:**

- Acceso restringido a la subred de aplicación mediante el mecanismo de restricción por subred configurado en la semana 1. El almacén no debe ser alcanzable desde internet.
- Estrategia de respaldo documentada: periodicidad, retención y pérdida máxima tolerable de datos.
- Utilizar el nivel de servicio gratuito disponible. Documentar sus límites.

### 2.3 Motor de scoring

Componente serverless activado por el evento de transacción entrante.

**Secuencia de ejecución:**

1. Recibir el evento de transacción.
2. Consultar el historial reciente de la cuenta en el almacén de transacciones.
3. Evaluar las cuatro reglas de detección.
4. Sumar los puntos de las reglas activadas.
5. Persistir el score y el detalle de las reglas activadas junto a la transacción.
6. Si el score supera el umbral, publicar un mensaje de apertura de caso.

**Reglas de detección:**

| Regla | Criterio de evaluación |
|---|---|
| **Velocidad** | Cantidad de transacciones de la cuenta dentro de una ventana temporal corta. |
| **Monto atípico** | Desviación del monto respecto al comportamiento histórico de la cuenta. |
| **Geo-imposible** | Relación entre la distancia que separa dos transacciones consecutivas y el tiempo transcurrido entre ellas. |
| **Comercio de riesgo** | Pertenencia del comercio o categoría destino a la lista de entidades marcadas. |

**Requerimientos:**

- **Umbral configurable.** No debe estar embebido en el código. Su modificación no puede requerir un nuevo despliegue. El valor seleccionado debe justificarse en función del compromiso entre falsos positivos y fraude no detectado.
- **Registro del detalle de activación.** Cada regla activada debe persistir los datos concretos que la activaron, no únicamente su identificador. El explicador de la semana 3 se construye sobre esta información; su ausencia impide implementarlo.

**Ejemplo de estructura mínima de registro por regla activada:** identificador de la regla, puntos aportados, y los valores observados que justificaron la activación (cantidad de transacciones en la ventana, monto observado frente a promedio histórico, distancia y tiempo transcurrido, etcétera).

### 2.4 Capa de mensajería

Se requieren dos mecanismos de mensajería con propósitos distintos. La distinción entre ambos debe estar comprendida y justificada.

**Distribución del evento de transacción.** La API publica un evento tras persistir la transacción y finaliza su ejecución. El motor de scoring reacciona a ese evento de forma independiente. La API no conoce ni espera el resultado del scoring.

**Cola de casos marcados.** El motor de scoring encola los casos que superan el umbral. El flujo de gestión de casos los consume a su propio ritmo. Este mecanismo debe garantizar que ningún caso se pierda ante la indisponibilidad del consumidor.

**Requerimiento de validación:** con el consumidor de casos detenido, la API debe continuar recibiendo y respondiendo transacciones con normalidad. Al restablecerse el consumidor, todos los casos marcados durante la indisponibilidad deben procesarse.

Documentar la diferencia entre notificar la ocurrencia de un evento y garantizar su procesamiento, y explicar por qué cada mecanismo corresponde a uno de esos propósitos.

### 2.5 Integración con la API de ingesta

Incorporar la publicación del evento en el punto de inserción identificado durante la semana 1. La secuencia de la API pasa a ser:

1. Recibir el payload.
2. Validar el cumplimiento del contrato.
3. Persistir la transacción cruda.
4. Publicar el evento de transacción.
5. Responder con acuse de recibo.

El paso 4 no debe bloquear la respuesta más allá de lo estrictamente necesario para confirmar la publicación.

### 2.6 Gestión de secretos

Migrar la totalidad de las cadenas de conexión, claves de acceso y credenciales a un gestor de secretos.

**Requerimientos:**

- Ningún secreto en el código, en el repositorio, ni en variables de entorno configuradas manualmente.
- Los componentes se autentican contra el gestor de secretos mediante la identidad gestionada configurada en la semana 1. No debe existir una credencial destinada a obtener credenciales.
- Auditar el historial de control de versiones para verificar la ausencia de secretos en commits anteriores.

### 2.7 Control de tasa en la ingesta

La API de ingesta está expuesta a internet. Debe implementarse una limitación de tasa que restrinja el número de peticiones aceptadas por origen en una ventana temporal.

**Justificación del requerimiento:** sin limitación de tasa, un actor malicioso puede saturar la API con transacciones sintéticas. Cada petición aceptada dispara un evento y una ejecución del motor de scoring, con el consiguiente consumo de crédito.

**Nota de alcance.** El proyecto no contempla una capa de gestión de API con nivel de servicio dedicado. La limitación de tasa se implementa en la aplicación o mediante los mecanismos de restricción disponibles en el servicio de aplicaciones. Documentar los límites aplicados y su justificación.

## 3. Entregables

| # | Entregable | Descripción |
|---|---|---|
| 1 | Almacén de transacciones desplegado | Con clave de partición, nivel de consistencia y política de expiración configurados. |
| 2 | Justificación del diseño de particionamiento | Qué consulta optimiza, cuál sacrifica, y por qué se descartaron las alternativas. |
| 3 | Almacén de casos desplegado | Modelo de datos completo con auditoría. Aislado de internet. |
| 4 | Estrategia de respaldo | Periodicidad, retención y pérdida máxima tolerable. |
| 5 | Motor de scoring operativo | Cuatro reglas implementadas, activado por evento. |
| 6 | Umbral configurable | Modificable sin redespliegue. Con justificación del valor seleccionado. |
| 7 | Registro de detalle de activación | Estructura persistida por cada regla activada, con los valores observados. |
| 8 | Capa de mensajería configurada | Distribución de eventos y cola de casos, con la distinción documentada. |
| 9 | Pipeline de extremo a extremo | Una transacción ingresa por la API y produce score y, si corresponde, caso. Sin intervención manual. |
| 10 | Prueba de desacoplamiento | Procedimiento reproducible que demuestre que la indisponibilidad del consumidor no afecta la ingesta ni produce pérdida de casos. |
| 11 | Secretos migrados | Sin credenciales en código, repositorio ni historial de versiones. |
| 12 | Limitación de tasa implementada | Con límites documentados y justificados. |
| 13 | Reporte de crédito consumido | Acumulado y proyección al cierre del proyecto. |
| 14 | Documento de decisiones actualizado | Incorporando las decisiones de esta semana. |

**Decisiones a incorporar en el documento de arquitectura:**

- Clave de partición seleccionada y alternativas descartadas.
- Nivel de consistencia y su impacto en latencia.
- Política de expiración y su relación con las ventanas temporales de las reglas.
- Justificación del uso de mensajería frente a invocación directa del motor de scoring.
- Valor del umbral y criterio aplicado.
- Diferencia funcional entre los dos mecanismos de mensajería utilizados.


## 4. Criterios de aceptación

**Desacoplamiento**

- [ ] La API responde a una transacción antes de que el motor de scoring finalice su ejecución. Demostrable mediante marcas de tiempo.
- [ ] Con el consumidor de casos detenido, la API continúa recibiendo y respondiendo transacciones.
- [ ] Al restablecerse el consumidor, los casos marcados durante la indisponibilidad se procesan sin pérdidas.

**Datos y escalabilidad**

- [ ] El motor de scoring consulta el historial de una única cuenta. Demostrable mediante la métrica de consumo de la consulta.
- [ ] La política de expiración elimina registros fuera de la ventana definida.
- [ ] El almacén de casos no es alcanzable desde internet. Verificado.
- [ ] Ambos almacenes operan dentro de los límites del nivel gratuito.

**Motor de scoring**

- [ ] Dos transacciones de una misma cuenta desde ubicaciones geográficamente incompatibles activan la regla correspondiente.
- [ ] Múltiples transacciones consecutivas de una misma cuenta activan la regla de velocidad.
- [ ] Un monto significativamente superior al histórico de la cuenta activa la regla de monto atípico.
- [ ] Una transacción hacia un comercio marcado activa la regla correspondiente.
- [ ] El umbral se modifica sin redespliegue y el comportamiento del sistema cambia en consecuencia.
- [ ] Cada regla activada persiste los valores concretos que la activaron, no únicamente su identificador.

**Seguridad y costo**

- [ ] No existe ninguna credencial en el código, en el repositorio ni en el historial de control de versiones.
- [ ] Los componentes acceden al gestor de secretos mediante identidad gestionada.
- [ ] Al superar el límite de tasa, la API responde con el código de estado correcto.
- [ ] El crédito acumulado al cierre de la semana 2 es inferior a 40 USD.


## 5. Consideraciones técnicas

**La invocación directa del motor de scoring desde la API constituye el error de diseño más frecuente en esta semana.** Produce un sistema que funciona y que incumple el requisito arquitectónico central. Toda la semana 3 se construye sobre el supuesto de que el pipeline está desacoplado.

**La clave de partición condiciona la escalabilidad del sistema.** Una consulta que recorre múltiples particiones para recuperar el historial de una cuenta funciona correctamente con volúmenes de prueba y falla en producción. Evaluar el consumo de la consulta, no únicamente su resultado.

**Una regla que no registra los valores que la activaron es una regla incompleta.** El explicador de la semana 3 se construye a partir de esa información. Su ausencia obliga a reprocesar transacciones o a rehacer el motor.

**El comportamiento ante fallos se verifica, no se supone.** Detener el consumidor de casos y observar el resultado es un requisito, no una recomendación. La primera ejecución de esa prueba habitualmente revela pérdida de mensajes.

**El consumo de crédito se acelera en esta semana.** El motor de scoring se ejecuta una vez por transacción. Las pruebas de carga deben dimensionarse en consecuencia y los recursos deben apagarse al cierre de cada jornada.
