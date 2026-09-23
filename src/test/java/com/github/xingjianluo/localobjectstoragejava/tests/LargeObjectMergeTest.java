package com.github.xingjianluo.localobjectstoragejava.tests;

import com.github.xingjianluo.localobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.localobjectstoragejava.service.MultipartUploadService;
import com.github.xingjianluo.localobjectstoragejava.service.ObjectService;
import com.github.xingjianluo.localobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.localobjectstoragejava.store.ChecksumUtil;
import com.github.xingjianluo.localobjectstoragejava.store.JsonCodec;
import com.github.xingjianluo.localobjectstoragejava.store.MetadataStore;
import com.github.xingjianluo.localobjectstoragejava.store.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 大对象合并：分片流式拷贝，内存占用不随对象总大小线性增长，
 * 合并后的整体 SHA-256 与原始数据一致。
 */
class LargeObjectMergeTest {

    private static final Logger log = LoggerFactory.getLogger(LargeObjectMergeTest.class);

    @TempDir
    Path tempDir;

    @Test
    void largeObjectMergedByStreamingWithConstantBuffer() throws Exception {
        int partSize = 1024 * 1024;          // 1 MiB / 片
        int totalParts = 30;                // 30 MiB 总大小
        int copyBuffer = 64 * 1024;         // 服务端合并缓冲 64 KiB
        String key = "large/30m-blob.bin";

        StorageProperties props = new StorageProperties();
        props.setRootDir(tempDir.toString());
        props.setCopyBufferSize(copyBuffer);
        JsonCodec codec = new JsonCodec();
        SessionStore sessions = new SessionStore(props, codec);
        MetadataStore metas = new MetadataStore(props, codec);
        BlobStore blobs = new BlobStore(props);
        MultipartUploadService uploads = new MultipartUploadService(sessions, blobs, metas, props);
        ObjectService objects = new ObjectService(metas, blobs);

        byte[] chunk = new byte[partSize];
        for (int i = 0; i < partSize; i++) {
            chunk[i] = (byte) (i * 31 + (i >> 4));
        }
        // 最后一片做短片，验证末尾分片大小规则
        int lastSize = partSize - 123;
        byte[] last = Arrays.copyOf(chunk, lastSize);
        for (int i = 0; i < lastSize; i++) {
            last[i] = (byte) (last[i] ^ 0x5A);
        }

        UploadSession session = uploads.createSession(key, totalParts, partSize);
        MessageDigest totalDigest = MessageDigest.getInstance("SHA-256");
        long expectedTotal = 0L;
        for (int n = 1; n <= totalParts; n++) {
            byte[] part = (n == totalParts) ? last : chunk;
            totalDigest.update(part);
            expectedTotal += part.length;
            uploads.uploadPart(session.getSessionId(), n, part.length,
                    ChecksumUtil.sha256Hex(part), new ByteArrayInputStream(part));
        }
        String expectedSha = HexFormat.of().formatHex(totalDigest.digest());

        long usedBeforeMerge = usedHeapKb();
        UploadSession done = uploads.completeSession(session.getSessionId(), expectedSha);
        long usedAfterMerge = usedHeapKb();

        try (var ch = objects.readCurrent(key)) {
            MessageDigest actualDigest = MessageDigest.getInstance("SHA-256");
            byte[] readBuf = new byte[256 * 1024];
            long total = 0;
            int r;
            try (var in = Channels.newInputStream(ch)) {
                while ((r = in.read(readBuf)) != -1) {
                    actualDigest.update(readBuf, 0, r);
                    total += r;
                }
            }
            assertEquals(expectedTotal, total);
            assertEquals(expectedSha, HexFormat.of().formatHex(actualDigest.digest()),
                    "30MiB 对象合并后整体校验必须一致");
        }

        // 内存增量远小于对象大小：流式合并的工作集是 KB 级，而不是 30MB 级。
        long heapDeltaKb = usedAfterMerge - usedBeforeMerge;
        log.info("[大对象合并] size={} bytes, copyBuffer={} bytes, heapDelta={} KB (允许 GC 抖动，断言 < 8MB)",
                expectedTotal, copyBuffer, heapDeltaKb);
        // 8MB 阈值相对 30MB 对象足够严格，同时容忍 GC/其它对象噪声。
        org.junit.jupiter.api.Assertions.assertTrue(heapDeltaKb < 8 * 1024,
                "合并内存增量不得随对象大小线性增长，实际增量 " + heapDeltaKb + " KB");
        log.info("[大对象完成] versionId={} contentId={} size={}",
                done.getCompletedVersionId(), done.getContentId(), done.getTotalSize());
    }

    private static long usedHeapKb() {
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1024;
    }
}
