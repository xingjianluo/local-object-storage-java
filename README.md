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
| `INVALID_RANGE` | 范围写法本身非法（负偏移、零/负长度、起止颠倒、Range 头无法解析）——**客户端把范围写错/取错**，应修正请求后重试 | 400 |
| `RANGE_NOT_SATISFIABLE` | 范围写法合法，但起始位置已达到/超过对象长度（空对象的任何范围同理）——**对象就这么长，重试也无济于事**，应向用户提示“已到末尾” | 416 |
| `STORAGE_CORRUPT` | 内部一致性错误（如引用计数为负） | 400 |

## 范围读取（Range Read）

整包与按段读取使用同一对端点，区别只在是否携带 `Range` 头：

- `GET /api/storage/objects/{key}/data` —— 最新版本
- `GET /api/storage/objects/{key}/versions/{versionId}/data` —— 任意历史版本

**不带 `Range` 头（整包）**：返回 `200`，流式输出完整内容。

**带 `Range` 头（按段，RFC 7233 单段语法）**：

```
Range: bytes=100-199    # 第 100~199 字节（闭区间）
Range: bytes=100-       # 第 100 字节到末尾
Range: bytes=-500       # 最后 500 字节
```

写法合法时返回 `206 Partial Content`。

### 成功返回里直接给出的字段（整包与按段、最新与历史版本语义一致）

客户端**不需要自己拿总长度和结束位置推算**，200 与 206 响应都带以下四个头：

| 响应头 | 含义 |
|---|---|
| `X-Object-Total-Size` | 对象（所读版本）的**总字节长度** |
| `X-Range-Offset` | 本次实际返回区间的起始偏移（整包为 `0`） |
| `X-Range-Length` | 本次实际返回的字节数（已对对象末尾做截断；整包=总长度） |
| `X-Has-More` | 本次区间之后**是否还有数据**（`true`/`false`；整包与读到末尾时为 `false`） |

按段响应另外带标准头：

| 响应头 | 含义 |
|---|---|
| `Content-Range: bytes <start>-<end>/<total>` | 本次实际返回的闭区间与对象总长度 |
| `Content-Length` | 本次返回的字节数（与 `X-Range-Length` 相同） |
| `Accept-Ranges: bytes` | 声明支持按段读取（整包响应也带） |

例：对 25 字节对象请求 `Range: bytes=20-999`，响应为
`206`、`Content-Range: bytes 20-24/25`、`X-Range-Length: 5`、`X-Has-More: false`
（请求长度超出末尾时自动截断，不视为错误）。客户端续传时只需：`X-Has-More: true`
就用上一段的 `offset + length` 作为下一段起点继续请求。

服务层 `ObjectStorageService.readLatest / readVersion / readLatestRange / readVersionRange`
统一返回 `RangeData(version, totalSize, offset, length, hasMore, stream)`，字段与上述响应头
一一对应：整包读取时 `offset=0, length=totalSize, hasMore=false`。

### 失败分类（三类稳定、可区分的返回）

| 情况 | errorCode | HTTP | 客户端建议 |
|---|---|---|---|
| Range **写法本身非法**：负偏移、零/负长度、起止颠倒（`bytes=5-2`）、`bytes=-0`、无法识别的 Range 头 | `INVALID_RANGE` | **400** | 请求写错了，修正范围参数后重试 |
| 写法合法，但**起始位置已达到/超过对象长度**（`bytes=100-*` 而对象只有 10 字节；空对象的任何范围同理） | `RANGE_NOT_SATISFIABLE` | **416** | 对象已读到头，重试无意义，应提示用户“到末尾了” |
| 对象不存在 / 版本不存在 / 底层内容缺失 | `OBJECT_NOT_FOUND` / `VERSION_NOT_FOUND` / `CONTENT_MISSING` | **404** | 资源问题，与范围写法无关 |

分类在两个阶段完成，保证语义稳定：HTTP 头先做**写法**解析（非法立即 400）；
通过写法校验后，由服务层在对象锁内对照“该版本的实际长度”判定起点越界（416）。
因此即使对象不存在，`bytes=5-2` 这类请求仍先得到 400 `INVALID_RANGE`；
而对不存在对象发一个写法合法的范围请求，得到的是 404 `OBJECT_NOT_FOUND`。

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
- **读钉（pin）机制**：读取流（整包与范围读取共用）打开前对内容加钉，关闭时释放；
  回收线程只删除 `refCount == 0 且无读钉` 的内容。因此读取与**删除 / 回滚 / 回收**交错时，
  **要么得到完整一致的一段数据，要么以 `CONTENT_MISSING`/`OBJECT_NOT_FOUND`/`VERSION_NOT_FOUND`
  等明确失败，绝不返回半截或拼接错位的内容**（有真实交错单测覆盖）。
- 范围请求在**同一把对象锁内**完成「版本解析 → 范围对照该版本长度解析 → 内容加钉打开定界流」
  三步，是一个原子步骤：不会读到“版本 A 的长度 + 版本 B 的内容”这类错位组合，也消除了
  先查长度再读之间的 TOCTOU 窗口。**回滚正在进行时的按段读取同样满足**：回滚只是“以历史
  版本内容追加一个新版本”，读钉保证被读版本的内容在流关闭前不会被回收，读流的长度与内容
  必定来自同一个版本。
- 定界读取流在打开时先按内容定位偏移，定位与读取都经过长度计数（`remaining`），
  区间之外的字节不会泄漏、区间之内的字节不会少给。
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
# 运行全部测试（36 个：乱序/重复分片、校验失败、过期、并发、删除回滚交错、
# 范围读取（开头/结尾/跨分片边界/单字节/空对象/各类非法范围）、
# “按段读取 + 删除 + 回滚 + 回收”真实交错、范围读取 HTTP 契约（200/206/400/416/404）、
# 大对象内存上界、崩溃重启对账、GC 等）
mvn test

# 只跑范围读取相关（服务层 + HTTP 契约）
mvn test -Dtest='RangeReadTest,HttpRangeReadTest'

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

# 整包下载（流式，200；四个 X- 头直接给出总长度/本次区间/hasMore）
curl -s -D- localhost:8080/api/storage/objects/demo.txt/data

# 范围下载（206，带 Content-Range 与 X-Object-Total-Size / X-Range-Offset /
# X-Range-Length / X-Has-More）
curl -s -D- -H 'Range: bytes=1-3' localhost:8080/api/storage/objects/demo.txt/data

# 范围写法非法 → 400 {"errorCode":"INVALID_RANGE",...}
curl -s -D- -H 'Range: bytes=3-1' localhost:8080/api/storage/objects/demo.txt/data
# 写法合法但起点越界 → 416 {"errorCode":"RANGE_NOT_SATISFIABLE",...}
curl -s -D- -H 'Range: bytes=100-200' localhost:8080/api/storage/objects/demo.txt/data
# 对象不存在 → 404 {"errorCode":"OBJECT_NOT_FOUND",...}
curl -s -D- -H 'Range: bytes=0-1' localhost:8080/api/storage/objects/nope/data
```

单测日志（`log.info`）会打印上传会话 ID、分片编号、sha256 校验依据、
版本链与引用计数变化，便于核对一致性断言。
