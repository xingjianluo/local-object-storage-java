# 本地对象存储服务

用本地磁盘模拟对象存储后端的 Spring Boot 服务，支持分片上传与断点续传、对象版本管理、
内容寻址去重，并保证在线程并发与异常中断下结果一致且可解释。

## 架构

```
StorageController          REST 接口
LocalUploadSessionManager  分片上传会话（创建/上传/合并/中止/过期回收）
LocalObjectStorageService  版本链（提交/读取/删除/回滚）
LocalContentStore          内容寻址存储（sha256 去重 + 引用计数 + 读钉）
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
| `STORAGE_CORRUPT` | 内部一致性错误（如引用计数为负） | 400 |

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
- **读钉（pin）机制**：读取流打开前对内容加钉，关闭时释放；回收线程只删除
  `refCount == 0 且无读钉` 的内容。因此回收期间的读取**要么得到完整一致的数据，
  要么以 `CONTENT_MISSING` 明确失败，绝不读到半成品**。
- 分片与元数据全部通过“临时文件 + 原子 rename”落盘，任何中断（进程崩溃、异常）
  都不会留下可见的半成品对象；未完成/中止/过期的上传不产生可见对象。
- `StorageGarbageCollector` 按 `local-storage.gc-interval` 周期回收过期会话与不可达内容。

## 可配置项（`local-storage.*`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `root-dir` | `./data/storage` | 存储根目录 |
| `session-ttl` | `PT30M` | 上传会话过期时间（ISO-8601 Duration） |
| `gc-interval` | `PT5M` | 回收任务间隔 |
| `max-part-size` | `67108864` | 单分片最大字节数 |

## 本地验证

```bash
# 运行全部测试（20 个：乱序/重复分片、校验失败、过期、并发、删除回滚交错、GC 等）
mvn test

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
curl -s localhost:8080/api/storage/objects/demo.txt/data
```

单测日志（`log.info`）会打印上传会话 ID、分片编号、sha256 校验依据、
版本链与引用计数变化，便于核对一致性断言。
