# 临床数据互操作服务（clinical-data-normalizer-java）

本地可运行的 Spring Boot 服务：以 HL7 v2 消息文本为输入，把不同来源的患者观测
数据归一到统一模型，并在授权范围内导出可共享的去标识化数据。

## 运行

```bash
mvn spring-boot:run          # 启动服务（application.properties 中 server.port=0 为随机端口，可改为固定端口）
mvn test                     # 运行全部测试（23 个）
```

### API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/ingest/batch` | 批量接收 HL7 消息，body: `{"messages": ["MSH|...", ...]}`，逐条返回 `ACCEPTED` 或 `REJECTED + reason` |
| POST | `/consents` | 登记授权，body: `{"patientId":"P1","allowedCodes":["2345-7"],"expiresAt":"2026-12-31T00:00:00Z"}` |
| POST | `/export` | 去标识化导出，body: `{"patientId":"P1","codes":["2345-7"]}`；越权返回 403 + 可区分的 `kind` |

## 消息处理规则

解析器（`hl7/Hl7Parser`）按 HL7 v2 标准语义还原：

- **分隔符声明**：MSH-1 定义字段分隔符，MSH-2 定义组件/重复/转义/子组件字符，
  MSH-2 本身按字面量保存，不做切分或转义还原；
- **重复段 / 重复字段**：段按 `\r\n|\r|\n` 切分，字段内 `~` 切出重复值，全部保留；
- **转义序列**：`\F\ \S\ \R\ \E\ \T\` 还原为对应分隔符，`\Xhh...\` 按十六进制
  （UTF-8）解码，`\H\ \N\` 显示标记安全忽略；未闭合或未知转义一律拒绝；
- **字符集**：MSH-18 仅接受 ASCII / UTF-8 / ISO-8859-1 系列声明，其他拒绝；
- **计量单位**：见下节换算策略。

**拒绝而非兜底**：结构不完整、单位无法识别、数值非法、代码不可映射、消息类型
不支持的消息一律拒绝，不使用默认值。拒绝原因可区分（`hl7/RejectionReason`）：

| 原因 | 触发条件 |
|---|---|
| `MALFORMED_STRUCTURE` | 缺 MSH/PID、缺必需字段（MSH-3/10、PID-3、OBX-1/2/3/5/6/14）、编码字符声明非法、转义未闭合、时间格式非法 |
| `UNKNOWN_UNIT` | OBX-6 单位不在 UCUM 白名单或与指标量纲不符 |
| `INVALID_VALUE` | OBX-2 非 NM、OBX-5 非数值 |
| `UNMAPPABLE_CODE` | OBX-3 代码无法映射到 LOINC |
| `UNSUPPORTED_MESSAGE` | 非 ORU 消息类型、不支持的字符集 |

任何一步失败则**整条消息**拒绝；批量投递中单条失败不影响其他消息。

## 幂等合并与冲突裁决

存储层（`store/ObservationStore`）保证重复投递、乱序到达、失败重试下最终观测
集合一致且与投递顺序无关：

- **观测级幂等键** `observationKey = 消息控制ID(MSH-10) + OBX set id`：同一消息
  重复投递时键与内容均相同，直接忽略，不产生重复观测；
- **冲突键** `conflictKey = 患者 + LOINC 代码 + 观测时刻`：同一冲突键只保留一条
  观测，裁决规则是全序的（因此与到达顺序无关）：
  1. `SOURCE_PRIORITY`：来源优先级高者胜（LAB=30 > HIS=20 > DEVICE=10，未知=0）；
  2. 优先级相同：`observationKey` 字典序大者胜（确定性打破对称）；
- **可追溯**：每次裁决写入 `ConflictDecision`（冲突键、胜方、负方、规则文本），
  冲突不得静默覆盖；存储内容真实变化时 `version` 才递增。

## 代码与单位映射策略

- **代码**（`normalize/CodeMapper`）：`LN/LOINC` 原样通过；本地系统（`L`、`99LAB`）
  查内置映射表（GLU→2345-7、SBP→8480-6、DBP→8462-4、HR→8867-4、TEMP→8310-5、
  HGB→718-7）；表外代码按 `UNMAPPABLE_CODE` 拒绝，保证映射可审计、可复现；
- **单位**（`normalize/UnitNormalizer`）：每个指标声明规范 UCUM 单位（血糖
  `mmol/L`、血压 `mm[Hg]`、心率 `/min`、体温 `Cel`、血红蛋白 `g/dL`）；同量纲单位
  按精确 `BigDecimal` 系数换算（如 `kPa→mm[Hg]`、`mg/dL→mmol/L` 血糖按 18.016、
  `[degF]→Cel` 仿射换算），结果统一保留 6 位小数（HALF_UP），同一输入永远得到
  同一输出；未知或量纲不符按 `UNKNOWN_UNIT` 拒绝。

## 去标识化边界

导出（`export/DeidExporter`）只输出单行记录：`假名|LOINC|值|单位|日期`。

- **假名化**：患者 ID 经 `HMAC-SHA256(密钥, patientId)` 截断 16 字节，同一患者
  跨导出可关联、无法反推原始 ID（密钥由 `deid.hmac-key` 配置，本地默认值仅用于
  开发）；
- **泛化**：观测时间截断为 UTC 日期，去除精确时间戳；
- **丢弃**：`messageId`、`observationKey`、`sourceId` 等准标识符不进入导出；
- **最小必要披露**：导出前逐指标校验授权（`consent/ConsentService`）——无授权
  `NO_CONSENT`、授权过期 `EXPIRED`、超出范围 `OUT_OF_SCOPE`，均拒绝（HTTP 403）；
- **可复现摘要**：记录排序后 `digest = SHA-256(scopeKey | 数据版本 | 记录)`，
  同一数据版本 + 同一授权范围的导出摘要必然一致，与输入顺序无关。

## 验证方法

`mvn test` 运行 23 个测试，日志中打印归一化结果与判定依据：

| 测试类 | 覆盖 |
|---|---|
| `Hl7ParserTest` | 编码字符、重复字段、转义还原、字符集；缺 MSH、非法编码字符、未知转义、不支持字符集的归类拒绝 |
| `NormalizationServiceTest` | 单位换算结果（打印归一化后模型）；未知单位/非数值/非法值类型/不可映射代码/缺字段/不支持消息类型的可区分拒绝 |
| `ObservationStoreTest` | 重复投递幂等（观测数不增）、两种投递顺序下冲突胜者一致、同优先级字典序决胜、乱序+重试收敛到同一集合（打印裁决规则） |
| `ExportAuthorizationTest` | 无授权/过期/越权的可区分拒绝；摘要可复现且与输入顺序无关；导出不含患者 ID/消息 ID/来源/精确时间 |
| `EndToEndTest` | REST 端到端：混合批量投递逐条归类、重放不产生重复观测、未授权 403、授权后导出摘要两次一致、越权 403 |
