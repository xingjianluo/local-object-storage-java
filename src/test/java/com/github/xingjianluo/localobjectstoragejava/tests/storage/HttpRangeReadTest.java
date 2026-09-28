package com.github.xingjianluo.localobjectstoragejava.tests.storage;

import com.github.xingjianluo.localobjectstoragejava.storage.LocalContentStore;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalObjectStorageService;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalUploadSessionManager;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageController;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.storage.UploadSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 范围读取的 HTTP 契约测试：200/206/400/416/404 状态码稳定可区分，
 * 成功响应直接携带对象总长度、本次区间与 hasMore（整包与按段一致）。
 *
 * <p>下载端点返回 {@code StreamingResponseBody}（异步写体），断言前需等待异步完成
 * 并做 asyncDispatch，否则只能看到状态/头、体偶发为空。</p>
 */
class HttpRangeReadTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private LocalUploadSessionManager sessions;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setRootDir(tempDir.toString());
        properties.setSessionTtl(Duration.ofMinutes(5));
        LocalContentStore contentStore = new LocalContentStore(properties);
        LocalObjectStorageService objects = new LocalObjectStorageService(properties, contentStore);
        sessions = new LocalUploadSessionManager(properties, contentStore, objects);
        mockMvc = MockMvcBuilders.standaloneSetup(new StorageController(sessions, objects)).build();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String upload(String key, byte[] content) {
        var spec = new UploadSessionManager.PartSpec(1, content.length, sha256(content));
        String sessionId = sessions.createSession(key, List.of(spec));
        sessions.uploadPart(sessionId, 1, new ByteArrayInputStream(content), content.length);
        return sessions.complete(sessionId);
    }

    /** 执行请求；若处理器开启了异步（StreamingResponseBody），等待其完成并转发，体才完整。 */
    private ResultActions call(MockHttpServletRequestBuilder req) throws Exception {
        ResultActions actions = mockMvc.perform(req);
        MvcResult result = actions.andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(10_000);
            return mockMvc.perform(asyncDispatch(result));
        }
        // 同步响应（如 400/404/416 的异常处理）：直接在原结果上断言
        return actions;
    }

    @Test
    void fullDownloadExposesMetadataHeaders() throws Exception {
        upload("doc.bin", "0123456789".getBytes(StandardCharsets.UTF_8));
        call(get("/api/storage/objects/doc.bin/data"))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-Object-Total-Size", "10"))
                .andExpect(header().string("X-Range-Offset", "0"))
                .andExpect(header().string("X-Range-Length", "10"))
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(content().bytes("0123456789".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rangeDownloadExposesContentRangeAndMetadataHeaders() throws Exception {
        upload("doc.bin", "0123456789".getBytes(StandardCharsets.UTF_8));
        // 闭区间 bytes=2-4 → "234"，后面还有数据
        call(get("/api/storage/objects/doc.bin/data").header("Range", "bytes=2-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-4/10"))
                .andExpect(header().longValue("Content-Length", 3))
                .andExpect(header().string("X-Object-Total-Size", "10"))
                .andExpect(header().string("X-Range-Offset", "2"))
                .andExpect(header().string("X-Range-Length", "3"))
                .andExpect(header().string("X-Has-More", "true"))
                .andExpect(content().bytes("234".getBytes(StandardCharsets.UTF_8)));
        // 读到末尾 hasMore=false；超出末尾自动截断
        call(get("/api/storage/objects/doc.bin/data").header("Range", "bytes=8-999"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 8-9/10"))
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(content().bytes("89".getBytes(StandardCharsets.UTF_8)));
        // 后缀范围
        call(get("/api/storage/objects/doc.bin/data").header("Range", "bytes=-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 7-9/10"))
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(content().bytes("789".getBytes(StandardCharsets.UTF_8)));
        // 开区间 bytes=start-
        call(get("/api/storage/objects/doc.bin/data").header("Range", "bytes=8-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 8-9/10"))
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(content().bytes("89".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void historicalVersionRangeUsesSameContract() throws Exception {
        byte[] v1Bytes = "version-one-0123456789".getBytes(StandardCharsets.UTF_8);
        String v1 = upload("doc.bin", v1Bytes);
        upload("doc.bin", "version-two-DIFFERENT!!".getBytes(StandardCharsets.UTF_8));
        call(get("/api/storage/objects/doc.bin/versions/" + v1 + "/data")
                        .header("Range", "bytes=8-10"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("X-Object-Total-Size", Integer.toString(v1Bytes.length)))
                .andExpect(header().string("X-Range-Offset", "8"))
                .andExpect(header().string("X-Range-Length", "3"))
                .andExpect(header().string("X-Has-More", "true"))
                .andExpect(content().bytes("one".getBytes(StandardCharsets.UTF_8)));
        // 历史版本整包契约一致
        call(get("/api/storage/objects/doc.bin/versions/" + v1 + "/data"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Range-Offset", "0"))
                .andExpect(header().string("X-Range-Length", Integer.toString(v1Bytes.length)))
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(content().bytes(v1Bytes));
    }

    @Test
    void malformedRangeIs400AndOutOfBoundsIs416AndNotFoundIs404() throws Exception {
        upload("doc.bin", "0123456789".getBytes(StandardCharsets.UTF_8));

        // 范围写法非法 → 400 INVALID_RANGE
        for (String bad : List.of("items=0-1", "bytes=5-2", "bytes=-0", "bytes=abc", "bytes=0-1,2-3")) {
            call(get("/api/storage/objects/doc.bin/data").header("Range", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"errorCode\":\"INVALID_RANGE\"}"));
        }
        // 写法合法但起点越界 → 416 RANGE_NOT_SATISFIABLE
        call(get("/api/storage/objects/doc.bin/data").header("Range", "bytes=100-200"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(content().json("{\"errorCode\":\"RANGE_NOT_SATISFIABLE\"}"));
        // 对象不存在仍是 404，不受 Range 影响
        call(get("/api/storage/objects/missing.bin/data").header("Range", "bytes=0-1"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"errorCode\":\"OBJECT_NOT_FOUND\"}"));
        // 历史版本不存在 → 404 VERSION_NOT_FOUND
        call(get("/api/storage/objects/doc.bin/versions/no-such-version/data")
                        .header("Range", "bytes=0-1"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"errorCode\":\"VERSION_NOT_FOUND\"}"));
    }

    @Test
    void emptyObjectFullIs200AndRangeIs416() throws Exception {
        upload("empty.bin", new byte[0]);
        call(get("/api/storage/objects/empty.bin/data"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Total-Size", "0"))
                .andExpect(header().string("X-Range-Length", "0"))
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(content().bytes(new byte[0]));
        call(get("/api/storage/objects/empty.bin/data").header("Range", "bytes=0-0"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(content().json("{\"errorCode\":\"RANGE_NOT_SATISFIABLE\"}"));
        // 空对象的后缀范围同样 416
        call(get("/api/storage/objects/empty.bin/data").header("Range", "bytes=-1"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(content().json("{\"errorCode\":\"RANGE_NOT_SATISFIABLE\"}"));
    }
}
