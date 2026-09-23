package com.github.xingjianluo.localobjectstoragejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端协议测试：走真实 Spring MVC + 磁盘存储。
 * 覆盖：创建会话、乱序分片、整体校验、读取、版本列表、回滚、删除、回收，
 * 以及各失败原因在 HTTP 层的可区分 errorCode。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "storage.root-dir=target/itest-storage-${random.uuid}",
        "storage.session-ttl-millis=3600000",
        "storage.blob-gc-grace-millis=0",
        "storage.reap-interval-millis=3600000"
})
class StorageRestIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StorageRestIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void fullProtocolOverHttp() throws Exception {
        String key = "photos/2026/holiday.bin";
        byte[] content = "The quick brown fox jumps over the lazy dog. 0123456789".getBytes();
        int partSize = 16;
        int total = (content.length + partSize - 1) / partSize;

        // 1. 创建会话
        MvcResult created = mockMvc.perform(post("/api/uploads")
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                new CreateBody(key, total, (long) partSize))))
                .andExpect(status().isOk()).andReturn();
        JsonNode session = json.readTree(created.getResponse().getContentAsString());
        String sid = session.get("sessionId").asText();
        log.info("[HTTP 创建会话] sid={} key={} totalParts={}", sid, key, total);

        // 2. 参数非法：totalParts=0
        mockMvc.perform(post("/api/uploads").contentType("application/json")
                        .content(json.writeValueAsString(new CreateBody(key, 0, (long) partSize))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PART_NUMBER_OUT_OF_RANGE")));

        // 3. 乱序上传：最后一片最先到，其余分片逆序到达，再把第 1 片重复一次（幂等）
        java.util.List<Integer> seq = new java.util.ArrayList<>();
        seq.add(total);
        for (int i = total - 1; i >= 1; i--) {
            seq.add(i);
        }
        seq.add(1);
        for (int number : seq.stream().mapToInt(Integer::intValue).toArray()) {
            int from = (number - 1) * partSize;
            int to = Math.min(content.length, from + partSize);
            byte[] part = java.util.Arrays.copyOfRange(content, from, to);
            MvcResult r = mockMvc.perform(put("/api/uploads/{sid}/parts/{n}", sid, number)
                            .header("X-Part-Size", part.length)
                            .header("X-Part-Checksum", sha256(part))
                            .content(part))
                    .andExpect(status().isOk()).andReturn();
            JsonNode body = json.readTree(r.getResponse().getContentAsString());
            log.info("[HTTP 分片] part={} 已收={} state={}", number,
                    body.get("parts").size(), body.get("state").asText());
        }

        // 4. 编号越界
        mockMvc.perform(put("/api/uploads/{sid}/parts/{n}", sid, total + 1)
                        .header("X-Part-Size", 1)
                        .content(new byte[]{1}))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PART_NUMBER_OUT_OF_RANGE")));

        // 5. 分片校验失败
        byte[] first = java.util.Arrays.copyOfRange(content, 0, partSize);
        // 分片校验失败：第 2 片内容真实 checksum 与声明不符，坏请求不落盘、不污染会话
        byte[] second = java.util.Arrays.copyOfRange(content, partSize, partSize * 2);
        mockMvc.perform(put("/api/uploads/{sid}/parts/2", sid)
                        .header("X-Part-Size", second.length)
                        .header("X-Part-Checksum", "00".repeat(32))
                        .content(second))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PART_CHECKSUM_MISMATCH")));
        // 之后用正确 checksum 重传第 2 片，会话恢复完整
        mockMvc.perform(put("/api/uploads/{sid}/parts/2", sid)
                        .header("X-Part-Size", second.length)
                        .header("X-Part-Checksum", sha256(second))
                        .content(second))
                .andExpect(status().isOk());

        // 6. 整体校验错误（单片全部有效，但整体 checksum 不符）
        mockMvc.perform(post("/api/uploads/{sid}/complete", sid)
                        .contentType("application/json")
                        .content("{\"checksum\":\"" + "ab".repeat(32) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOTAL_CHECKSUM_MISMATCH")));

        // 7. 正确完成
        MvcResult completed = mockMvc.perform(post("/api/uploads/{sid}/complete", sid)
                        .contentType("application/json")
                        .content("{\"checksum\":\"" + sha256(content) + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode doneNode = json.readTree(completed.getResponse().getContentAsString());
        String versionId = doneNode.get("completedVersionId").asText();
        log.info("[HTTP 完成] sid={} state=COMPLETED versionId={} contentId={}",
                sid, versionId, doneNode.get("contentId").asText());

        // 8. 重复完成 -> 409 SESSION_ALREADY_COMPLETED
        mockMvc.perform(post("/api/uploads/{sid}/complete", sid)
                        .contentType("application/json")
                        .content("{\"checksum\":\"" + sha256(content) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("SESSION_ALREADY_COMPLETED")));

        // 9. 读取当前对象，内容必须完整一致
        MvcResult readResult = mockMvc.perform(get("/api/objects/current").param("key", key))
                .andExpect(status().isOk()).andReturn();
        byte[] actual = readResult.getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(content, actual);
        log.info("[HTTP 读取] key={} bytes={}", key, actual.length);

        // 10. 版本列表
        MvcResult listResult = mockMvc.perform(get("/api/objects").param("key", key))
                .andExpect(status().isOk()).andReturn();
        JsonNode meta = json.readTree(listResult.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(1, meta.get("versions").size());
        org.junit.jupiter.api.Assertions.assertEquals(versionId, meta.get("currentVersionId").asText());

        // 11. 指定版本读取不存在 -> 404 VERSION_NOT_FOUND
        mockMvc.perform(get("/api/objects/versions/{vid}", "v-nope").param("key", key))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VERSION_NOT_FOUND")));

        // 12. 回滚到 v1（只有一个版本，等价复用 blob）
        MvcResult rb = mockMvc.perform(post("/api/objects/rollback").param("key", key)
                        .contentType("application/json")
                        .content("{\"versionId\":\"" + versionId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rbNode = json.readTree(rb.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(
                doneNode.get("contentId").asText(), rbNode.get("contentId").asText(),
                "回滚必须复用同一 blob");
        log.info("[HTTP 回滚] newVersion={} origin={} contentId={}",
                rbNode.get("versionId").asText(), versionId, rbNode.get("contentId").asText());

        // 13. 删除旧版本，再读 -> 409 VERSION_DELETED
        mockMvc.perform(delete("/api/objects/versions/{vid}", versionId).param("key", key))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/objects/versions/{vid}", versionId).param("key", key))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VERSION_DELETED")));

        // 14. 手动回收
        MvcResult reap = mockMvc.perform(post("/api/admin/reap"))
                .andExpect(status().isOk()).andReturn();
        log.info("[HTTP 回收] {}", reap.getResponse().getContentAsString());

        // 15. 读取不存在对象 -> 404 OBJECT_NOT_FOUND
        mockMvc.perform(get("/api/objects/current").param("key", "missing-no-such"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("OBJECT_NOT_FOUND")));
    }

    @Test
    void abortedUploadLeavesNoObject() throws Exception {
        String key = "aborted/only.bin";
        byte[] part = "abcd".getBytes();
        MvcResult created = mockMvc.perform(post("/api/uploads").contentType("application/json")
                        .content(json.writeValueAsString(new CreateBody(key, 2, 4L))))
                .andExpect(status().isOk()).andReturn();
        String sid = json.readTree(created.getResponse().getContentAsString()).get("sessionId").asText();
        mockMvc.perform(put("/api/uploads/{sid}/parts/1", sid)
                        .header("X-Part-Size", part.length)
                        .header("X-Part-Checksum", sha256(part)).content(part))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/uploads/{sid}", sid)).andExpect(status().isNoContent());

        // 会话已删除 -> NOT_FOUND
        mockMvc.perform(get("/api/uploads/{sid}", sid))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SESSION_NOT_FOUND")));
        // 对象不可见
        mockMvc.perform(get("/api/objects/current").param("key", key))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("OBJECT_NOT_FOUND")));
        log.info("[HTTP 中止] sid={} 已清理且对象不可见", sid);
    }

    private record CreateBody(String objectKey, Integer totalParts, Long partSize) {
    }
}
