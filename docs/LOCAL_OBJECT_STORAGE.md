# 本地对象存储服务（Local Object Storage）

用本地磁盘模拟对象存储后端的 Spring Boot 服务，提供**分片上传 / 断点续传、对象版本、内容去重、
并发一致性与资源回收**能力。全部元数据与内容均落在本地文件系统，不依赖外部数据库或对象存储。

---

## 1. 总体结构

```
<storage.root-dir>/
├── blobs/                         # 内容寻址存储（去重的物理数据）
│   ├── tmp/                       # 合并临时文件（进程启动时清理遗留）
│   └── xx/
│       ├── <contentId>            # 不可变内容文件，xx 为 contentId 前两位
│       └── <contentId>.refs/      # 该内容的引用标记文件（引用计数的事实来源）
│           └── <encodedKey>__<versionId>
├── objects/
│   └── <encodedKey>.json          # 每个对象一份元数据：追加式版本链 + 当前版本指针
└── uploads/
    └── xx/
        └── <sessionId>/
            ├── session.json       # 上传会话（原子写入）
            └── parts/
                ├── part-00001     # 已上传分片
                └── upload-*.tmp   # 分片落盘临时文件（失败即删）
```

- **contentId = 内容的 SHA-256**。相同内容在整个系统里只保留一份物理文件。
- 所有 JSON 元数据都走「写临时文件 → `ATOMIC_MOVE` 发布」，崩溃后不会出现半个文件。
- 引用计数不落内存账本，而是以 `<contentId>.refs/` 目录下的标记文件为准，服务重启后可重建、可对账。

---

## 2. 上传与合并协议

### 2.1 协议流程

```
POST   /api/uploads                         创建会话
PUT    /api/uploads/{sid}/parts/{n}          上传分片（可乱序、可重复、可重试）
POST   /api/uploads/{sid}/complete           校验并合并 -> 新版本原子可见
DELETE /api/uploads/{sid}                    中止会话（分片全部清理，不产生对象）
GET    /api/uploads/{sid}                    查询会话状态与已收分片
```

创建会话时声明：`objectKey`、`totalParts`、`partSize`，并返回 `sessionId` 与 `expiresAtMillis`。

### 2.2 分片规则

- 分片编号 `1..totalParts`；越界返回 `PART_NUMBER_OUT_OF_RANGE`。
- **非最后一片**大小必须恰好等于 `partSize`；**最后一片**大小必须在 `1..partSize`。
  不符返回 `PART_SIZE_MISMATCH`。
- 每个分片通过请求头携带：
  - `X-Part-Size`：声明字节数；服务端用有界输入流强制上限，超出即 `PART_SIZE_MISMATCH`，防止伪造声明写爆磁盘。
  - `X-Part-Checksum`：该分片 SHA-256 十六进制；与实际内容不符返回 `PART_CHECKSUM_MISMATCH`。
- **乱序**：分片可按任意编号顺序上传，服务端按编号排序保存。
- **重复 / 重试**：同编号分片再次上传时，以**本次请求体的真实内容**与已存分片比较
  （大小 + SHA-256）：
  - 内容相同 → 幂等成功，不产生多余记录；
  - 内容不同 → `PART_CONFLICT`，绝不互相覆盖。
- 校验失败的分片只存在于临时文件中，失败即删除，不会写入会话。

### 2.3 完成与合并

`POST /complete` 请求体 `{"checksum":"<整体 SHA-256 十六进制>"}`（checksum 可省略，则只保证分片齐全）。

完成时服务端：

1. 校验会话状态（INIT 且未过期）与全部 N 个分片是否收齐，否则 `PART_MISSING`；
2. **按编号顺序流式拼接**到 `blobs/tmp` 临时文件，边拷贝边用 `DigestOutputStream` 计算整体哈希，
   拷贝缓冲固定为 `storage.copy-buffer-size`（默认 64KiB），**内存占用与对象总大小无关**；
3. 比对总大小与整体 checksum，不符返回 `TOTAL_CHECKSUM_MISMATCH` 并删除临时文件；
4. 以内容哈希为 contentId 原子发布：已存在同内容 blob 则丢弃临时文件直接复用（去重）；
5. 在对象元数据中**追加新版本**、登记 blob 引用、更新当前版本指针——三者在同一把对象锁内、
   同一次原子 JSON 落盘生效。**只有到这一步，对象才对读取方可见**；
6. 清理分片数据（保留终态 `session.json` 至会话过期，用于区分重复完成）。

中断（进程崩溃 / 未调用 complete / 会话 abort / 过期）的上传永远不会产生可见对象：
未完成的对象元数据不存在，分片随会话在 abort 时同步删除、在过期时由回收器清理。

### 2.4 失败原因码（可区分）

| errorCode | HTTP | 含义 |
|---|---|---|
| `SESSION_NOT_FOUND` | 404 | 会话不存在或已被清理 |
| `SESSION_EXPIRED` | 409 | 会话超过 TTL |
| `SESSION_COMPLETED` / `SESSION_ALREADY_COMPLETED` | 409 | 会话已完成（后者用于重复 complete） |
| `SESSION_ABORTED` | 409 | 会话已被中止 |
| `PART_NUMBER_OUT_OF_RANGE` | 400 | 分片编号 `<1` 或 `>totalParts` |
| `PART_SIZE_MISMATCH` | 400 | 分片大小不符 / 超出声明 / 非定长片不合规 |
| `PART_CHECKSUM_MISMATCH` | 400 | 单片 SHA-256 与声明不符 |
| `PART_CONFLICT` | 409 | 同编号分片已存在但内容不同 |
| `PART_MISSING` | 409 | 完成时分片未收齐 |
| `TOTAL_CHECKSUM_MISMATCH` | 400 | 合并后总大小或整体 checksum 不符 |
| `OBJECT_NOT_FOUND` | 404 | 对象不存在 / 内容已不可读 |
| `OBJECT_DELETED` | 409 | 对象存在但当前无可读版本 |
| `VERSION_NOT_FOUND` | 404 | 版本标识不存在 |
| `VERSION_DELETED` | 409 | 版本已删除（重复删除 / 读 / 回滚被删版本） |

---

## 3. 版本与去重规则

### 3.1 版本链

每个对象 key 对应一份 `objects/<key>.json`：

- `versions`：**只追加**的版本记录列表，每条含 `versionId / contentId / size / checksum /
  createdAtMillis / deleted / deletedAtMillis / originVersionId`；
- `currentVersionId`：当前可读版本指针。

同名对象的每次成功上传都产生一个**新版本**，历史版本保留并可：

- 读取：`GET /api/objects/versions/{vid}?key=`；
- 列举：`GET /api/objects?key=`（含删除标记与当前指针）；
- 删除：`DELETE /api/objects/versions/{vid}?key=`。

### 3.2 删除语义

- 删除**历史版本**：只释放该版本引用，不影响当前版本。
- 删除**当前版本**：当前指针回退到版本链中最近一个未删除版本；若没有，则置空，
  读当前对象返回 `OBJECT_DELETED`。
- 版本记录只打 `deleted` 标记（保留审计轨迹），重复删除返回 `VERSION_DELETED`。

### 3.3 回滚语义

`POST /api/objects/rollback?key=` body `{"versionId":"..."}`：

- **不复活**被删旧记录、不改写历史，而是追加一个新版本，其 `contentId` 指向目标历史版本内容；
- 因为同内容 contentId 相同，回滚**自动复用同一份 blob**，引用计数 +1；
- `originVersionId` 记录来源版本，便于审计；
- 目标版本不存在 / 已删除分别返回 `VERSION_NOT_FOUND` / `VERSION_DELETED`，失败不追加版本。

### 3.4 内容去重与引用计数

- contentId 相同即同内容：跨版本、跨对象都只保留一份物理 blob。
- 引用计数 = `<contentId>.refs/` 目录中标记文件个数；每个未删除版本恰好一个标记。
- 新版本 / 回滚 → `acquireRef`；删除版本 → `releaseRef`。
  引用登记与版本元数据在同一对象锁、同一次原子落盘内完成，保证
  「版本可见 ⇔ 引用存在」。
- 回收器周期性以对象元数据为权威对账磁盘引用数（差异会打印 WARN），零引用 blob 在宽限期后物理删除。

---

## 4. 并发与资源回收语义

### 4.1 锁的层次与顺序（无死锁）

- **会话锁**（每个 sessionId 一把）：串行化同一会话的分片提交 / 合并 / 中止。
- **对象 key 锁**（每个 key 一把）：串行化版本链的追加 / 删除 / 回滚，读取时也在锁内确定 `contentId`。
- **blob 全局锁**：串行化 blob 发布、引用标记变更、活动读者计数与物理删除。
- 锁顺序全局唯一：**会话锁 → key 锁 → blob 锁**；读取路径只取 **key 锁 → blob 锁**，
  不存在反向获取，因此不会形成环路。

### 4.2 可见性与交错操作

- **并发上传相同内容**：合并临时文件各自独立；发布时 contentId 相同，只有一个原子 rename 成功，
  其余复用已发布 blob。最终物理文件一份、每个版本各自一个引用。
- **并发重复 complete**：由会话锁串行，恰好一个成功，其余返回 `SESSION_ALREADY_COMPLETED`。
- **上传与读取交错**：新版本在元数据原子落盘前，读者只能读到旧版本；落盘后读到新版本。
  读者先在 key 锁内确定 contentId，再在 blob 锁内登记「活动读者」并打开文件，
  因此读到的要么是完整旧内容，要么是完整新内容，绝不会是半成品。
- **删除 / 回滚交错**：所有变更串行于 key 锁；当前指针永远指向未删除版本或为空；
  blob 在引用数归零且**所有活动读者退出**后才会物理删除。

### 4.3 活动读者保护

打开 blob 时 `activeReaders[contentId]++`，通道关闭时 `--`（通道包装类保证异常关闭也计数）。
回收删除前若仍有读者，回收线程在 blob 锁上等待（单次回收最多等待 5 秒），等待期间：

- 已持有通道的读者继续读取完整旧文件（Unix 文件解除链接不影响已打开句柄，且本实现额外做了计数保护）；
- 新读者发现文件已不存在会明确收到 `OBJECT_NOT_FOUND`，不会读到残缺数据。

### 4.4 回收器（Reaper）

定时任务 `MaintenanceTask`（`fixedDelay = storage.reap-interval-millis`，默认 5 分钟，启动后延迟一个周期执行）：

1. **过期会话**：
   - INIT 且过期 → 删除整个会话目录（中断上传无残留、无对象）；
   - COMPLETED / ABORTED 终态 → 保留到 TTL 之后再清理 `session.json`，期间重复请求仍能得到可区分错误。
2. **暂存文件**：`blobs/tmp` 在服务启动时清理上一进程遗留的合并临时文件。
3. **不可达 blob**：以全部对象元数据为权威计算存活 contentId 集合，
   零引用内容进入宽限期（`storage.blob-gc-grace-millis`，默认 60s，覆盖并发提交窗口）后，
   在无活动读者时物理删除 blob 与其 refs 目录。

也可通过 `POST /api/admin/reap` 手动立即触发一次完整回收（测试/运维用），返回
`{"expiredSessions":n,"unreachableBlobs":m}`。

---

## 5. 可配置项

`src/main/resources/application.properties`：

| 配置键 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `storage.root-dir` | `STORAGE_ROOT` | `data/storage` | 存储根目录 |
| `storage.session-ttl-millis` | `STORAGE_SESSION_TTL` | `86400000`(24h) | 上传会话有效期 |
| `storage.reap-interval-millis` | `STORAGE_REAP_INTERVAL` | `300000`(5min) | 后台回收周期 |
| `storage.copy-buffer-size` | `STORAGE_COPY_BUFFER` | `65536`(64KiB) | 合并/校验拷贝缓冲（内存上界，与对象大小无关） |
| `storage.blob-gc-grace-millis` | `STORAGE_BLOB_GC_GRACE` | `60000`(1min) | 零引用 blob 回收前宽限期 |

---

## 6. HTTP API 速览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/uploads` | 创建会话，body `{objectKey,totalParts,partSize}` |
| PUT | `/api/uploads/{sid}/parts/{n}` | 上传分片，headers `X-Part-Size`、`X-Part-Checksum` |
| POST | `/api/uploads/{sid}/complete` | 完成合并，body `{checksum}` |
| DELETE | `/api/uploads/{sid}` | 中止会话 |
| GET | `/api/uploads/{sid}` | 查询会话 |
| GET | `/api/objects/current?key=` | 读当前版本（二进制流） |
| GET | `/api/objects/versions/{vid}?key=` | 读指定版本 |
| GET | `/api/objects?key=` | 版本链与当前指针 |
| DELETE | `/api/objects/versions/{vid}?key=` | 删除版本 |
| POST | `/api/objects/rollback?key=` | 回滚版本，body `{versionId}` |
| POST | `/api/admin/reap` | 手动回收 |

> 对象 key 通过查询参数传递，因此 key 中允许包含 `/`、空格、中文等任意字符。

错误响应统一为：`{"errorCode":"<上表中的码>","message":"<人类可读原因>"}`。

---

## 7. 本地验证方法

### 7.1 构建与测试

```bash
mvn test
```

测试矩阵（共 15 个用例，全部通过）：

- `MultipartUploadServiceTest`（核心协议，7 个）：
  乱序 + 重复分片、单片校验 / 大小 / 越界失败、同编号冲突、整体校验失败无副作用、
  跨版本去重与引用计数 + GC、回滚复用 blob、过期会话拒绝与回收。
- `ConcurrencyTest`（并发一致性，4 个）：
  8 线程并发相同内容去重、6 路并发重复完成、上传与 6 路持续读取交错、删除与回滚 20 次交错。
- `LargeObjectMergeTest`（1 个）：30MiB / 30 分片流式合并，整体 SHA-256 一致，
  合并内存增量远小于对象大小（KB 级工作集）。
- `StorageRestIntegrationTest`（端到端 HTTP，2 个）：走真实 Spring MVC + 磁盘，
  验证完整协议、各错误码 HTTP 映射、中止后对象不可见。

单测日志按要求打印**上传会话（sid/key/TTL/状态）、每个分片（编号/大小/SHA-256/已收数量）
与校验依据（声明值 vs 实际值、整体 checksum）、版本提交与引用计数变化、回收对账结果**，
可用以下命令观察：

```bash
mvn test -Dtest=MultipartUploadServiceTest
grep -E "创建会话|分片|合并|版本|回收|被拒绝" target/surefire-reports/com.github*.txt
```

### 7.2 curl 手工演练

```bash
mvn spring-boot:run

# 10 字节内容，4+4+2 三片
h1=$(printf abcd | sha256sum | cut -d' ' -f1)
h2=$(printf efgh | sha256sum | cut -d' ' -f1)
h3=$(printf ij   | sha256sum | cut -d' ' -f1)
total=$(printf abcdefghij | sha256sum | cut -d' ' -f1)

sid=$(curl -s -X POST localhost:8080/api/uploads -H 'Content-Type: application/json' \
  -d '{"objectKey":"demo/file.bin","totalParts":3,"partSize":4}' | sed 's/.*"sessionId":"\([^"]*\)".*/\1/')

printf ij   | curl -s -X PUT "localhost:8080/api/uploads/$sid/parts/3" -H "X-Part-Size:2" -H "X-Part-Checksum:$h3" --data-binary @-
printf abcd | curl -s -X PUT "localhost:8080/api/uploads/$sid/parts/1" -H "X-Part-Size:4" -H "X-Part-Checksum:$h1" --data-binary @-
printf efgh | curl -s -X PUT "localhost:8080/api/uploads/$sid/parts/2" -H "X-Part-Size:4" -H "X-Part-Checksum:$h2" --data-binary @-

curl -s -X POST "localhost:8080/api/uploads/$sid/complete" -H 'Content-Type: application/json' -d "{\"checksum\":\"$total\"}"
curl -s "localhost:8080/api/objects/current?key=demo/file.bin"
curl -s "localhost:8080/api/objects?key=demo/file.bin"
curl -s -X POST "localhost:8080/api/admin/reap"
```
