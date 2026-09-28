package com.github.xingjianluo.localobjectstoragejava.tests.storage;

import com.github.xingjianluo.localobjectstoragejava.storage.ObjectStorageService;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageController;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageException;
import com.github.xingjianluo.localobjectstoragejava.storage.UploadSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层测试：范围失败在状态码与 errorCode 上稳定可区分，
 * 200/206 响应都直接给出总长度、本次区间与 hasMore。
 */
class RangeReadHttpTest {

    private ObjectStorageService objects;
    private MockMvc mvc;

    private static ObjectStorageService.VersionInfo version(long size) {
        return new ObjectStorageService.VersionInfo("v-1", "k", "c-1", size, 0L, false);
    }

    @BeforeEach
    void setUp() {
        objects = mock(ObjectStorageService.class);
        UploadSessionManager sessions = mock(UploadSessionManager.class);
        mvc = MockMvcBuilders.standaloneSetup(new StorageController(sessions, objects)).build();
    }

    @Test
    void rangeSuccessExposesSegmentHeaders() throws Exception {
        byte[] slice = "234".getBytes();
        when(objects.statLatest("k")).thenReturn(version(10));
        when(objects.readLatestRange(eq("k"), eq(2L), eq(3L)))
                .thenReturn(new ObjectStorageService.RangeData(
                        version(10), 10, 2, 3, true, new ByteArrayInputStream(slice)));

        var result = mvc.perform(get("/api/storage/objects/k/data").header("Range", "bytes=2-4"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-4/10"))
                .andExpect(header().string("X-Object-Total-Size", "10"))
                .andExpect(header().string("X-Object-Offset", "2"))
                .andExpect(header().string("X-Object-Length", "3"))
                .andExpect(header().string("X-Object-Has-More", "true"))
                .andExpect(header().string("X-Object-Version-Id", "v-1"))
                .andExpect(content().bytes(slice));
    }

    @Test
    void fullSuccessExposesSameSegmentHeaders() throws Exception {
        when(objects.readLatest("k"))
                .thenReturn(new ObjectStorageService.ObjectData(
                        version(5), new ByteArrayInputStream("hello".getBytes())));

        var result = mvc.perform(get("/api/storage/objects/k/data"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Total-Size", "5"))
                .andExpect(header().string("X-Object-Offset", "0"))
                .andExpect(header().string("X-Object-Length", "5"))
                .andExpect(header().string("X-Object-Has-More", "false"))
                .andExpect(content().string("hello"));
    }

    @Test
    void malformedRangeIs400() throws Exception {
        when(objects.statLatest("k")).thenReturn(version(10));

        mvc.perform(get("/api/storage/objects/k/data").header("Range", "bytes=5-2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RANGE"));
    }

    @Test
    void startBeyondSizeIs416() throws Exception {
        when(objects.statLatest("k")).thenReturn(version(10));
        when(objects.readLatestRange(eq("k"), eq(100L), anyLong()))
                .thenThrow(new StorageException(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE,
                        "起始位置 100 超出对象长度 10"));

        mvc.perform(get("/api/storage/objects/k/data").header("Range", "bytes=100-200"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.errorCode").value("RANGE_NOT_SATISFIABLE"));
    }

    @Test
    void openEndedRangeAtExactSizeIs416() throws Exception {
        // bytes=10- 解析为 {10, 0}：起点恰好等于长度，必须归 416 而不是 400
        when(objects.statLatest("k")).thenReturn(version(10));
        when(objects.readLatestRange(eq("k"), eq(10L), eq(0L)))
                .thenThrow(new StorageException(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE,
                        "起始位置 10 超出对象长度 10"));

        mvc.perform(get("/api/storage/objects/k/data").header("Range", "bytes=10-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.errorCode").value("RANGE_NOT_SATISFIABLE"));
    }

    @Test
    void missingObjectIs404EvenWithRange() throws Exception {
        when(objects.statLatest("nope"))
                .thenThrow(new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND, "x"));

        mvc.perform(get("/api/storage/objects/nope/data").header("Range", "bytes=0-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OBJECT_NOT_FOUND"));
    }
}
