# 本地对象存储服务

用本地磁盘模拟对象存储后端的 Spring Boot 服务，支持分片上传与断点续传、对象版本管理、
内容寻址去重、整包/按字节范围的流式读取，并保证在线程并发与异常中断下结果一致且可解释。

## 架构

```
StorageController          REST 接口（上传会话、版本管理、整包/范围下载）
LocalUploadSessionManager  分片上传会话（创建/上传/合并/中止/过期回收）
LocalObjectStorageService  版本链（提交/读取/范围读取/删除/回滚）
LocalContentStore          内容寻址存储（sha256 去重 + 引用计数 + 读钉 + 定界流）
StorageReconciler          启动对账（校正引用计数、回收崩溃残留、清理临时文件）
StorageGarbageCollector    定期回收过期会话与不可达内容
```

磁盘布局（`<rootDir>` 默认 `./data/storage`）：

```
blobs/<sha256>            内容数据（相同内容全局仅一份）
blobs/<sha256>.ref        该内容的引用计数
objects/<base64url(key)>/meta.json   对象版本链元数据
sessions/<sessionId>/session.json    上传会话元数据
sessions/<sessionId>/part-<n>        已校验的分片文件
tmp/                      写入中的临时文件
```

## 上传与合并协议

1. **创建会话** `POST /api/storage/sessions`
   ```json
   {
     "objectKey": "dir/file.bin",
     "parts": [
       {"partNumber": 1, "size": 100, "sha256": "<hex>"},
       {"partNumber": 2, "size": 100, "sha256": "<hex>"}
     ]
   }
   ```
   分片编号必须恰好覆盖 `1..N`，返回 `sessionId` 与过期时间（`local-storage.session-ttl`）。
2. **上传分片** `PUT /api/storage/sessions/{sessionId}/parts/{n}?size=<bytes>`（请求体为分片内容）。
   分片可**乱序、重复、重试**上传：服务端流式写入临时文件并计算 sha256，校验通过后原子落位；
   重复上传同一分片（内容一致）幂等成功，天然支持断点续传。
3. **合并完成** `POST /api/storage/sessions/{sessionId}/complete`。
   全部分片到齐后，合并前会**重新校验每个分片的 sha256**（防御磁盘损坏），
   然后以 `SequenceInputStream` 流式合并写入内容存储——**内存占用与对象总大小无关**。
   合并成功前对象对读取方完全不可见。
4. **中止** `POST /api/storage/sessions/{sessionId}/abort`：清理临时分片，不产生任何可见对象。

### 可区分的失败原因

所有失败以 `StorageException.ErrorCode` 标识（REST 返回 `{"errorCode", "message"}`）：

| 错误码 | 含义 | HTTP |
|---|---|---|
| `SESSION_NOT_FOUND` | 会话不存在 | 404 |
| `SESSION_EXPIRED` | 会话已过期 | 409 |
| `SESSION_ALREADY_COMPLETED` | 重复完成 | 409 |
| `SESSION_ALREADY_ABORTED` | 会话已中止 | 409 |
| `PART_NUMBER_OUT_OF_RANGE` | 分片编号越界 | 400 |
| `PART_CHECKSUM_MISMATCH` | 分片 sha256 校验失败（报文含期望/实际值） | 400 |
| `PART_SIZE_MISMATCH` | 分片大小与声明不符 | 400 |
| `PARTS_MISSING` | 完成时缺少分片 | 400 |
| `OBJECT_NOT_FOUND` / `VERSION_NOT_FOUND` | 对象/版本不存在 | 404 |
| `CONTENT_MISSING` | 底层内容缺失或已回收 | 404 |
| `INVALID_RANGE` | 范围写法非法（负偏移、零/负长度、起止颠倒、Range 头无法识别） | 400 |
| `RANGE_NOT_SATISFIABLE` | 范围写法合法，但起始位置达到/超过对象长度（含空对象） | 416 |
| `STORAGE_CORRUPT` | 内部一致性错误（如引用计数为负） | 400 |

## 范围读取（Range Read）

整包与按段读取使用同一对端点，区别只在是否携带 `Range` 头：

- `GET /api/storage/objects/{key}/data` —— 最新版本
- `GET /api/storage/objects/{key}/versions/{versionId}/data` —— 任意历史版本

**不带 `Range` 头**：返回 `200`，流式输出完整内容，响应头含
`Content-Length`（对象总长度）与 `Accept-Ranges: bytes`（声明支持按段读取）。

**带 `Range` 头**（RFC 7233 单段语法）：

```
Range: bytes=100-199    # 第 100~199 字节（闭区间）
Range: bytes=100-       # 第 100 字节到末尾
Range: bytes=-500       # 最后 500 字节
```

返回 `206 Partial Content`，响应头：

| 响应头 | 含义 |
|---|---|
| `Content-Range: bytes <start>-<end>/<total>` | 本次实际返回的区间与对象总长度（标准头） |
| `Content-Length` | 本次返回的字节数（`end - start + 1`） |
| `Accept-Ranges: bytes` | 声明支持按段读取 |
| `X-Object-Version-Id` | 本次读取命中的版本标识（最新版本或历史版本） |
| `X-Object-Total-Size` | 对象总长度，客户端无需另发请求查询 |
| `X-Object-Offset` | 本次实际返回区间的起始偏移 |
| `X-Object-Length` | 本次实际返回的字节数（请求超长时为截断后的值） |
| `X-Object-Has-More` | 本次区间之后是否还有数据（`true`/`false`） |

`200` 整包响应同样携带这组 `X-Object-*` 头（此时 `Offset=0`、`Length=Total-Size`、
`Has-More=false`），因此客户端可以用**完全相同的字段**处理整包与按段、最新版本与
历史版本，不需要自行解析 `Content-Range` 或拿总长度推算。

服务层 `ObjectStorageService.readLatestRange / readVersionRange` 返回的 `RangeData`
携带 `version / totalSize / offset / length / hasMore / stream`，与上述响应头一一对应；
整包结果 `ObjectData` 也提供同名的 `totalSize() / offset() / length() / hasMore()`
（整包即区间 `[0, size)`，`hasMore=false`），语义与按段完全一致。
请求长度超出对象末尾时自动截断到末尾（`length` 为实际长度，不视为错误）。

### 范围读取的失败分类（三类稳定、可区分的返回）

| 情况 | errorCode | HTTP | 客户端建议 |
|---|---|---|---|
| 范围写法错误：负偏移、零/负长度、`end < start`（起止颠倒）、Range 头格式无法识别、多区间 | `INVALID_RANGE` | 400 | 参数错误，修正前不要原样重试 |
| 范围写法合法，但起始位置 ≥ 对象长度（空对象的任何范围、`bytes=<size>-` 均同此） | `RANGE_NOT_SATISFIABLE` | 416 | 对象可能增长，之后可重试；响应语义同 RFC 7233 |
| 对象不存在 / 版本不存在 / 底层内容缺失 | `OBJECT_NOT_FOUND` / `VERSION_NOT_FOUND` / `CONTENT_MISSING` | 404 | 与范围类失败明确区分 |

错误响应体统一为 `{"errorCode": "...", "message": "..."}`。分类规则在最新版本与任意
历史版本、HTTP 层与服务层保持一致；判定顺序为先查对象/版本（404），再判负偏移（400），
再判起点是否越界（416），最后判起点合法时的零/负长度（400）。

**内存约束**：整包与按段下载均为流式输出（定界 `InputStream` + `StreamingResponseBody`），
堆占用是固定的小缓冲区，与对象大小无关——几百 MB 的对象连续读取也不会顶高堆
（有单测断言读取 96MB 对象期间堆增长 < 32MB）。

## 版本与去重规则

- 同名对象的每次成功 `complete` 产生一个**新版本**（UUID 版本号），历史版本可按版本标识
  读取 `GET /objects/{key}/versions/{versionId}/data`、删除 `DELETE .../versions/{versionId}`、
  回滚 `POST /objects/{key}/rollback/{versionId}`（以历史内容生成一个新版本，不破坏版本链）。
- 实际数据按 **sha256 内容寻址**：相同内容全局只保留一份，版本链中的每个版本持有一个引用。
- 引用计数协议：`commitVersion` 前先 `retain` 再落元数据；删除版本/对象时先落元数据再
  `release`——保证**元数据可见 ⇒ 内容必可达**，杜绝引用计数错误与版本链错乱。
- 引用计数持久化在 `<sha256>.ref` 中，重启后自动恢复。

## 并发与回收语义

- 每个对象、每个会话、每个内容各有一把锁；并发上传相同内容只会落盘一份（去重命中日志可查）。
- **读取与删除/回滚/回收同时发生时的约定**：读取（整包或按段、最新或历史版本）
  **要么返回完整一致的一段并给出与内容严格对应的元信息（totalSize/offset/length/hasMore），
  要么以明确的错误码失败（`OBJECT_NOT_FOUND`/`VERSION_NOT_FOUND`/`CONTENT_MISSING`），
  绝不返回半截数据、拼接数据或“版本 A 的长度 + 版本 B 的内容”这类前后对不上的结果。**
- **读钉（pin）机制**：读取流打开前对内容加钉，关闭时释放；回收线程只删除
  `refCount == 0 且无读钉` 的内容。因此删除版本/对象（先落元数据再 `release`）与
  回收（`sweep`）交错时，已经打开的读取流一定能读完整个区间；流关闭后内容才可能被回收。
- **按段读取的原子性**：范围读取在对象锁内一次性完成“定位版本 → 校验范围 → 内容加钉
  → 打开定界流”，区间长度与内容来自同一版本，不会读到错位组合。
- **回滚进行中的按段读取**：回滚（取历史版本 → retain → 追加新版本并落元数据）在同一把
  对象锁内原子完成，按段读取在锁上排队，因此只会观察到“回滚前的最新版本”或
  “回滚后的新版本”二者之一，不会观察到回滚的中间状态；回滚目标版本恰好在并发中被删时，
  回滚与读取都收到明确的 404 类失败。
- 分片与元数据全部通过“临时文件 + 原子 rename”落盘，任何中断（进程崩溃、异常）
  都不会留下可见的半成品对象；未完成/中止/过期的上传不产生可见对象。
- `StorageGarbageCollector` 按 `local-storage.gc-interval` 周期回收过期会话与不可达内容。

## 异常中断后的启动对账

进程在写入或合并中途被强杀时，磁盘上可能留下三类残留，重启后由 `StorageReconciler`
（在两个存储组件加载完磁盘状态之后、对外服务之前）统一对账：

1. **以版本链为准校正引用计数**：统计全部对象版本链对内容的实际引用次数，重写
   `<sha256>.ref`。崩溃窗口（`put` 落盘后计数前、`retain` 后元数据落盘前、
   删除版本后 `release` 前）造成的计数虚高/虚低都被校正——**仍被引用的内容绝不误删**。
2. **回收无引用残留**：磁盘上存在但无任何版本引用的内容按 0 引用纳入管理，
   对账后立即 `sweep` 回收——**残留不会永远占盘**。若此后客户端重试未完成的
   `complete`，分片文件仍在，`put` 会重新落盘内容，流程自愈。
3. **清理临时文件**：`tmp/` 下的写入临时文件、会话目录下的 `upload-*.part` /
   `session.json.tmp`、对象目录下的 `meta.json.tmp` 在启动时全部清除。

对账发现“版本链引用了内容但数据文件缺失”时保留计数并记录错误日志，
读取该对象会以 `CONTENT_MISSING` 明确失败而非返回错误数据。

## 可配置项（`local-storage.*`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `root-dir` | `./data/storage` | 存储根目录 |
| `session-ttl` | `PT30M` | 上传会话过期时间（ISO-8601 Duration） |
| `gc-interval` | `PT5M` | 回收任务间隔 |
| `max-part-size` | `67108864` | 单分片最大字节数 |

## 本地验证

```bash
# 运行全部测试（38 个：乱序/重复分片、校验失败、过期、并发、删除回滚交错、
# 范围读取开头/结尾/跨分片边界/单字节/空对象/各类非法范围、
# 按段读取+删除+回滚+回收真实交错、回滚进行中按段读取、
# HTTP 层 400/416/404 区分与 X-Object-* 响应头、大对象内存上界、崩溃重启对账、GC 等）
mvn test

# 只跑范围相关测试（服务层 + HTTP 层）
mvn test -Dtest='RangeReadTest,RangeReadHttpTest'

# 启动服务
mvn spring-boot:run

# 手工验证一次完整上传（示例）
P1=$(printf 'hello-' | sha256sum | cut -d' ' -f1)
SID=$(curl -s -X POST localhost:8080/api/storage/sessions \
  -H 'Content-Type: application/json' \
  -d "{\"objectKey\":\"demo.txt\",\"parts\":[{\"partNumber\":1,\"size\":6,\"sha256\":\"$P1\"}]}" \
  | jq -r .sessionId)
printf 'hello-' | curl -s -X PUT "localhost:8080/api/storage/sessions/$SID/parts/1?size=6" \
  -H 'Content-Type: application/octet-stream' --data-binary @-
curl -s -X POST localhost:8080/api/storage/sessions/$SID/complete

# 整包下载（200，X-Object-* 头：Offset=0、Length=Total-Size、Has-More=false）
curl -s -D- localhost:8080/api/storage/objects/demo.txt/data

# 范围下载（206，Content-Range + X-Object-Total-Size/Offset/Length/Has-More）
curl -s -D- -H 'Range: bytes=1-3' localhost:8080/api/storage/objects/demo.txt/data

# 起点越界：416 + errorCode=RANGE_NOT_SATISFIABLE（范围写法本身合法，对象增长后可重试）
curl -s -D- -H 'Range: bytes=100-200' localhost:8080/api/storage/objects/demo.txt/data

# 起止颠倒：400 + errorCode=INVALID_RANGE（写法错误，不应原样重试）
curl -s -D- -H 'Range: bytes=5-2' localhost:8080/api/storage/objects/demo.txt/data

# 对象不存在即使带 Range 也仍是 404 + OBJECT_NOT_FOUND
curl -s -D- -H 'Range: bytes=0-1' localhost:8080/api/storage/objects/missing/data
```

单测日志（`log.info`）会打印上传会话 ID、分片编号、sha256 校验依据、
版本链与引用计数变化，便于核对一致性断言。
