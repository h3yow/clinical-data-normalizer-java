# 临床数据互操作服务（HL7 v2 → 统一模型 → 去标识化导出）

本地可运行的 Spring Boot 服务。接收 **HL7 v2** 消息文本，将不同来源的患者观测
归一到统一模型（LOINC + UCUM），按患者做纵向合并与幂等重放，支持**结果更正与
撤回**、**版本历史与按版本追溯导出**，并在授权范围内导出**去标识化**数据。

- Java 21 / Spring Boot 3.4 / 无外部依赖（内存存储，进程内即可运行）
- 构建：`mvn package`
- 测试：`mvn test`（48 个用例，覆盖畸形消息、重复投递、越权导出、更正/撤回、
  跨批乱序收敛、按历史版本导出复盘）

---

## 1. 快速开始

```bash
mvn spring-boot:run            # 或 mvn package && java -jar target/*.jar
```

### 1.1 投递 HL7 批量消息

```bash
curl -X POST http://localhost:8080/api/hl7/ingest \
     -H 'Content-Type: text/plain' \
     --data-binary @samples/batch.hl7
```

请求体为原始 HL7 文本，段结束符支持标准 CR，也容忍 LF / CRLF。
可含 `FHS..FTS` / `BHS..BTS` 批量信封（信封内多条 MSH），也可直接以 MSH 起始。

返回逐条消息的处理结果：

```json
{"status":"ACCEPTED","messageControlId":"MSG-1001","acceptedCount":2,
 "conflictDecisions":[], "errorStage":null,"errorReason":null,"errorMessage":null}
```

`status` 取值：

| 状态 | 含义 |
|---|---|
| `ACCEPTED` | 已归一并合并，`acceptedCount` 为实际登记的观测事件数（内容去重后） |
| `DUPLICATE` | 消息控制号已处理过（重复投递/重试），不写任何状态 |
| `DEFERRED` | 消息内全部更正/撤回意图均未找到目标：事件已登记为**待生效**，未当作普通新增；`mergeDecisions` 给出可区分原因码 |
| `REJECTED` | 被拒绝，`errorStage`（PARSE/NORMALIZE）与 `errorReason` 给出可区分原因 |

每条消息的处理结果还携带两类可追溯判定：

- `conflictDecisions`：多来源普通新增之间的冲突裁决（见 §3.2）；
- `mergeDecisions`：更正/撤回/被压制新增的判定，含 `kind`
  （`CORRECTION_APPLIED` / `CORRECTION_DEFERRED` / `WITHDRAWAL_APPLIED` /
  `WITHDRAWAL_DEFERRED` / `ADD_SUPERSEDED_BY_CORRECTION` /
  `ADD_SUPERSEDED_BY_WITHDRAWAL`）与机器可读的 `reasonCode`
  （如 `CORRECTION_TARGET_NOT_FOUND`、`WITHDRAWAL_TARGET_NOT_FOUND`）。

### 1.2 去标识化导出

```bash
curl "http://localhost:8080/api/export?grantId=grant-all-demo&patientId=P-0001&categories=vital_signs,laboratory"
```

内置演示授权（`AuthorizationService#seedDemoGrants`，可按需替换为真实授权源）：

| grantId | 患者范围 | 数据大类 | 有效期 |
|---|---|---|---|
| `grant-vitals-demo` | P-0001 | vital_signs | 一年 |
| `grant-all-demo` | P-0001,P-0002 | vital_signs, laboratory | 一年 |
| `grant-expired-demo` | P-0001 | 全部 | 2020 年（已过期） |

### 1.3 版本历史与按版本追溯导出

```bash
# 版本历史：版本号、变化时间、相对上一版新增/更正/撤回了什么
curl "http://localhost:8080/api/export/versions?grantId=grant-all-demo&patientId=P-0001"

# 按历史版本复盘导出（与当前导出走同一套授权校验与去标识化边界）
curl "http://localhost:8080/api/export?grantId=grant-all-demo&patientId=P-0001&categories=vital_signs&version=2"
```

- 患者的当前有效观测集合**每真正变化一次**产生一个新版本（从 1 开始单调递增，
  不回退）；重复投递、内容重放、无目标的更正/撤回登记都不产生版本。
- 历史版本自**该版本产生之时**起即可复盘（内存存储，进程重启后历史不保留）；
  快照不可变，之后投递再多新数据也不影响按该版本导出的结果与摘要。
- `version` 缺省时导出当前版本；指定版本时：
  - 版本号不是正整数 → **400** `INVALID_VERSION`；
  - 该患者不存在此版本（含超出当前范围、或属于其他患者的版本号）→
    **404** `VERSION_NOT_FOUND`；
  - 授权失败（不存在/过期/越患者/越大类）→ **403**，与当前导出完全同一套校验，
    且授权校验先于版本判定。

---

## 2. 消息处理规则

### 2.1 解析层（`hl7` 包，严格模式，绝不默认值兜底）

- **分隔符**取自每条消息的 MSH-1（字段符 `|`）与 MSH-2（组件 `^`、重复 `~`、
  转义 `\`、子组件 `&`，2~4 个且互不相同）。
- **字段编号与 HL7 标准完全一致**：`field(3)` 即 MSH-3 / PID-3 / OBX-3；
  字段列表第 0 位为占位。前导与尾随空字段都保留，避免字段错位。
- **重复段**（多个 OBX）与**重复字段**（OBX-5 的 `~` 重复）均按出现顺序保留，
  每个重复值各产生一条观测。
- **转义序列**按标准还原：`\F\`→`|`、`\S\`→`^`、`\T\`→`&`、`\R\`→`~`、
  `\E\`→`\`、`\.br\`→换行、`\Xhhhh\`→按 MSH-18 字符集解码的字节、`\Z..\`→本地转义。
  未知转义、缺少结束符、非法十六进制一律拒绝。
- **字符集**由 MSH-18 声明（支持 ASCII / ISO-8859-1 / UTF-8 / GB18030 / GBK）。
  MSH-18 按标准字段语义定位：MSH 段按字段符切分后，MSH-2（编码字符）在下标 1，
  因此 **MSH-18 在下标 17**；声明可重复（取第一重复为主字符集），组件形式取第一组件。
  写在其他字段位置（如 MSH-19）的声明**不予采信**。
  缺省按 US-ASCII **严格**解码，出现非法字节直接拒绝。
  未登记的字符集名以 `UNSUPPORTED_CHARSET` 拒绝。

解析层错误原因（`ParseErrorReason`，相互可区分）：

`EMPTY_PAYLOAD`、`UNEXPECTED_FRAME_START`、`MSH_TOO_SHORT`、
`INVALID_ENCODING_CHARACTERS`、`UNSUPPORTED_CHARSET`、`MALFORMED_TEXT_ENCODING`、
`INVALID_ESCAPE_SEQUENCE`、`UNCLOSED_BATCH_FRAME`、`INVALID_SEGMENT_NAME`、
`INCOMPLETE_SEGMENT`。

### 2.2 归一化层（`normalize` 包）

仅接受 `MSH-9 = ORU^R01`。硬性校验，任一不满足即以独立原因拒绝：

- MSH-10 控制号、MSH-3 发送应用、MSH-7 消息时间必填；
- 必须存在 PID（PID-3 非空）、OBR、至少一个 OBX；
- OBX-2 必须为 `NM`（撤回事件除外）；OBX-11 接受 `F`（Final，普通新增）/
  `C`（Corrected，更正）/`D`（Delete，撤回）；
- OBX-3 必须命中编码目录；OBX-6 单位必须命中单位目录；
- OBX-5 每个重复必须是**有限实数**（`abc`、`NaN`、`Infinity`、指数越界分别归类为
  `NON_NUMERIC_OBSERVATION_VALUE` 与 `VALUE_OUT_OF_RANGE`）；空值拒绝；
- 生效时间取 OBX-14，缺省依次回退 OBR-7、MSH-7；DTM 至少精确到分钟，
  不带时区按 UTC 处理（文档化约定）。

完整原因见 `NormalizationErrorReason`（共 22 项，如 `UNKNOWN_OBSERVATION_CODE`、
`UNKNOWN_UNIT`、`MISSING_UNIT`、`INCONSISTENT_UNIT_ACROSS_REPETITIONS` 等）。
**不使用默认值兜底**：结构缺失、单位未知、数值非法的消息被整条拒绝，
单条失败不影响同批其它消息。

### 2.3 编码映射策略（`CodeCatalog`）

- 统一指标使用 **LOINC**；映射键为 `(来源系统, 来源编码, 编码体系)`。
- 来源系统 `*` 表示官方 LOINC 通配映射；**来源特定映射优先于通配**。
- 目录为内置固定版本（可审计、可复现）；未收录编码以 `UNKNOWN_OBSERVATION_CODE` 拒绝。

| 统一编码 | 指标 | 大类 | 收录的来源编码示例 |
|---|---|---|---|
| 8867-4 | Heart rate | vital_signs | LN:8867-4；HIS_A/LOCAL:HR |
| 8310-5 | Body temperature | vital_signs | LN:8310-5；HIS_A/LOCAL:TEMP |
| 8480-6 | Systolic blood pressure | vital_signs | LN:8480-6；HIS_A/LOCAL:SBP |
| 2345-7 | Glucose in Serum/Plasma | laboratory | LN:2345-7；LAB_X/LOCAL:GLU |

### 2.4 单位映射策略（`UnitCatalog`）

每个指标有唯一 **UCUM** 规范单位；换算采用仿射公式
`规范值 = 值 × factor + offset`：

| 指标 | 规范单位 | 收录来源单位与换算 |
|---|---|---|
| 8867-4 心率 | `/min` | bpm、/min、beats/min（×1） |
| 8310-5 体温 | `Cel` | Cel/degC/C（×1）；degF/F（×5/9，offset=−160/9） |
| 8480-6 收缩压 | `mm[Hg]` | mm[Hg]/mmHg/torr（×1）；kPa（×7.500616827） |
| 2345-7 血糖 | `mg/dL` | mg/dL、mg/100ml（×1）；mg/L（×0.1）；mmol/L（×18.0156） |

别名匹配大小写不敏感、做空白归一；未收录单位以 `UNKNOWN_UNIT` 拒绝，不做猜测。

---

## 3. 纵向合并、幂等与冲突裁决（`ingest` 包）

### 3.1 幂等与顺序无关（三重保证）

1. **消息级去重**：以 MSH-10 为幂等键。控制号已处理即返回 `DUPLICATE`，
   不写任何状态——重复投递、乱序到达、失败后重试都安全，且不会产生重复版本。
2. **内容级去重**：对 `患者|统一编码|生效时刻(epoch 秒)|规范值|规范单位` 计算
   SHA-256 内容指纹；相同观测事件永不重复登记（即使来自不同消息）。
3. **顺序无关的槽位合并**：以 `患者|编码|生效时刻` 为槽位，槽位内登记全部
   观测事件（新增/更正/撤回），**槽位有效值是事件集合的纯函数**，
   与消息内 OBX 顺序、投递先后、批次拆分方式均无关。

### 3.2 冲突裁决规则（确定且可追溯，不静默覆盖）

同一槽位出现**不同数值**的普通新增时按以下**全序**比较，高者获胜：

1. 来源系统权威性优先级：`HIS_A(30) > LAB_X(20) > OTHER(10) > 未登记来源(0)`；
2. 平手时比较消息控制号（字典序）；
3. 再平手比较观测内容指纹。

每次冲突都生成 `ConflictDecision`（含胜/败双方指纹、控制号、来源、裁决理由），
随投递响应返回并记入患者冲突历史。**数值相同**的重复观测只走指纹去重，
保持既有获胜者不变、不产生版本变化，也不产生噪声裁决。

### 3.3 结果更正与撤回（OBX-11 = C / D）

来源方可针对某个患者、某个指标、某个观测时刻（即槽位）再发更正或撤回声明。
取舍规则与冲突裁决、重复投递**一致且不互相打架**——槽位内全部事件按固定层级裁决：

```
撤回（终态） >  更正  >  普通新增
```

同类事件内部仍按 §3.2 的全序（来源优先级 → 控制号 → 指纹）裁决。具体语义：

- **更正（C）**：替换该槽位当前有效值。更正优先于任何普通新增——即使新增来自
  更高优先级来源（显式临床修正意图优先于来源权威性启发式）。
- **撤回（D）**：把槽位从当前有效集合移除，且是**槽位终态**：同槽位后续的
  普通新增与更正都不会使其复活（确需重新上报应使用新的观测时刻）。
- **无目标判定**：槽位没有任何已登记的普通新增（F）时，更正/撤回**不生效**，
  也**绝不悄悄当成普通新增**：事件登记为待生效，消息返回 `DEFERRED` 状态与
  可区分原因码（`CORRECTION_TARGET_NOT_FOUND` / `WITHDRAWAL_TARGET_NOT_FOUND`），
  并记入患者合并历史。目标到达后，待生效事件按同一确定性规则自动适用——
  因此「先发更正、后发结果」与「先发结果、后发更正」最终状态完全一致。
- 被更正掉、被撤回的值不再出现在当前有效集合与任何导出中；
  同一患者、同一指标、同一时刻最终只保留一条有效观测。

每次更正/撤回/压制都生成 `MergeDecision`（kind + reasonCode + 判定依据），
随响应返回并持久记录，审计可追溯。

### 3.4 版本历史

患者的**当前有效观测集合**每真正变化一次就固化一个不可变版本快照
（`VersionSnapshot`：版本号、变化时间、相对上一版的变化清单、该版有效指纹集）。
变化类别：`ADDED`（槽位首次生效）、`CORRECTED`（被更正替换）、
`REPLACED`（被更高优先级新增替换）、`WITHDRAWN`（被撤回）。
版本号单调递增、不回退；重复投递、内容重放、无目标事件的登记都不产生版本。

把同一批消息拆成不同批次、换不同顺序、反复重投或失败后重试，
最终当前有效集合必然一致（中间版本序列可能不同，但最终有效集合相同、
版本号只增不减）。

---

## 4. 授权与去标识化边界（`auth` / `export` 包）

### 4.1 最小必要披露校验（按顺序，失败原因可区分）

`GRANT_NOT_FOUND` → `GRANT_EXPIRED`（expiresAt 须**严格晚于**当前时刻，
到期当时即过期）→ `PATIENT_OUT_OF_SCOPE` → `CATEGORY_OUT_OF_SCOPE`
（申请的每个大类都必须在授权范围内，部分越权则整体拒绝，HTTP 403）。

### 4.2 去标识化边界（导出结果不含直接标识符）

- 输出结构 `DeidentifiedObservation` **根本不包含**患者 ID、来源系统、消息控制号；
- 患者身份替换为带服务盐的确定性伪名：
  `PSEUDO-` + SHA-256(`v1|salt|patientId`) 前 16 位，跨次导出一致且不可逆；
- 时间精度降低到**年份**（`effectiveYear`），避免日期级重标识；
- 仅返回授权大类内的观测，完全相同的去标识化行合并；
- **导出口径为当前有效观测集合**：被更正掉、被撤回、被冲突裁决掉的值不出现；
- 版本历史视图（`/api/export/versions`）只含版本号、变化时间与指标级变化
  （大类/编码/类别），同样不含患者标识、来源、消息控制号；
- 未入库患者不泄露存在性，返回空集。

服务盐通过 `app.export.pseudonym-salt`（环境变量 `APP_PSEUDONYM_SALT`）配置，
生产环境必须覆盖默认值。

### 4.3 可复现摘要

导出包含 `digest`：对「伪名 | 数据版本 | 排序后的授权范围 | 逐行规范化内容」
做 SHA-256。观测行按全字段确定性排序，因此**同一数据版本 + 同一授权范围**
（含大类传入顺序不同）下，导出集合与 digest 完全一致；数据变化（版本变化）
或范围变化时 digest 必然改变。

**按历史版本导出**（`version` 参数）使用同一摘要公式：只要当时披露过某版本的
digest，之后任意时刻按该版本复盘导出的 digest 必然逐位一致，审计方可独立复核。

---

## 5. 验证方法

```bash
mvn test
```

| 测试类 | 覆盖内容 |
|---|---|
| `Hl7ParserTest` | 重复段/重复字段/转义/字符集还原；MSH-18 标准位置声明（UTF-8/8859-1）被采信、非标准位置声明不采信；空负载、错误帧、短 MSH、非法编码字符、未闭合信封、未知转义、不支持/非法字符集等**分类拒绝** |
| `ObservationNormalizerTest` | 单位换算（温度仿射、kPa、mmol/L）、重复值、本地码→LOINC、时间回退、OBX-11 F/C/D 事件类别映射；16 种语义错误原因逐一区分；日志打印归一化结果与判定依据 |
| `IngestionServiceTest` | 三次重复投递只产生一条观测；乱序到达结果一致；同内容跨消息指纹去重；冲突裁决可追溯；畸形/语义错误拒绝后修正重试成功 |
| `CorrectionWithdrawalTest` | 更正替换有效值、撤回移出有效集合、无目标更正/撤回的 DEFERRED 可区分判定、待生效事件目标到达后自动适用、更正优先于高优先级新增、撤回终态、跨批拆批+乱序+重投收敛 |
| `VersionHistoryTest` | 版本随有效变化单调递增、变化清单可追溯；按历史版本导出冻结可复现（digest 与当时一致、后续新数据不影响）；超范围/他人版本 VERSION_NOT_FOUND、非法版本 INVALID_VERSION、历史导出走同一套授权 |
| `ExportServiceTest` | 去标识化字段与年份粒度；大类最小必要；缺失/过期/越患者/越大类四类拒绝；digest 同版本同范围可复现、版本/范围变化即改变；过期边界 |
| `EndToEndIntegrationTest` | MockMvc 全链路：HTTP 投递、DUPLICATE 重放、403 越权、畸形消息返回原因；更正→撤回→版本历史→按版本导出→版本不存在 404/非法版本 400 |

测试日志（SLF4J，默认 INFO 即输出）逐条打印归一化结果与判定依据，例如：

```
[归一化结果] patient=P-0001 code=2345-7(...) value=100.0 unit=mg/dL time=... fp=...
[冲突裁决] slot=P-0004|8867-4|... 胜者=HIS_A/MSG-W2 败者=LAB_X/MSG-W1 依据='新观测覆盖：来源优先级 30 vs 20 ...'
[更正判定] CORRECTION_APPLIED slot=P-C1|8867-4|... 消息=MSG-C1 原因码=APPLIED —— ...
[无目标更正] CORRECTION_DEFERRED ... 原因码=CORRECTION_TARGET_NOT_FOUND —— ...
[版本历史] v2 at=... 变化=[CORRECTED vital_signs 8867-4] —— ...
[授权拒绝] ... -> CATEGORY_OUT_OF_SCOPE
```

手工端到端：

```bash
mvn spring-boot:run
curl -X POST localhost:8080/api/hl7/ingest -H 'Content-Type: text/plain' --data-binary @samples/batch.hl7
curl -X POST localhost:8080/api/hl7/ingest -H 'Content-Type: text/plain' --data-binary @samples/batch.hl7   # -> DUPLICATE
curl "localhost:8080/api/export?grantId=grant-all-demo&patientId=P-0001&categories=vital_signs,laboratory"

# 更正与撤回（MSG-3002 更正心率 72->75，MSG-3004 撤回体温）
curl -X POST localhost:8080/api/hl7/ingest -H 'Content-Type: text/plain' --data-binary @samples/corrections.hl7
# 版本历史：可看到 ADDED / CORRECTED / WITHDRAWN 各版本
curl "localhost:8080/api/export/versions?grantId=grant-all-demo&patientId=P-0001"
# 按历史版本复盘（digest 与该版本当时导出一致）
curl "localhost:8080/api/export?grantId=grant-all-demo&patientId=P-0001&categories=vital_signs&version=1"
# 不存在的版本 -> 404 VERSION_NOT_FOUND
curl "localhost:8080/api/export?grantId=grant-all-demo&patientId=P-0001&categories=vital_signs&version=99"
```

---

## 6. 包结构

```
hl7/       HL7 v2 词法/结构解析、转义与字符集（MSH-18 标准字段语义）、解析错误原因
model/     统一观测模型 Observation（含内容指纹）与事件类别 ObservationKind
normalize/ 编码目录、单位目录与换算、ORU^R01 语义归一化（OBX-11 F/C/D）、归一化错误原因
ingest/    批量投递、患者纵向状态（槽位事件模型）、消息/内容去重、确定性冲突裁决、
           更正/撤回判定（MergeDecision）、版本历史（VersionSnapshot/VersionChange）
auth/      授权登记与最小必要校验、授权错误原因
export/    去标识化、确定性伪名、版本化可复现摘要、按历史版本追溯导出、版本历史视图
web/       REST 控制器与异常→HTTP 映射
```
