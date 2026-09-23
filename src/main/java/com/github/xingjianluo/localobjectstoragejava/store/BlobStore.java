package com.github.xingjianluo.localobjectstoragejava.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

/**
 * 内容块（blob）存储：按内容 SHA-256 寻址，带引用计数与活动读者保护。
 * 同内容物理上只保留一份。
 */
@org.springframework.stereotype.Component
public class BlobStore {

    public static final class StoredContent {
        private final String contentId;
        private final long size;
        private final String checksum;

        public StoredContent(String contentId, long size, String checksum) {
            this.contentId = contentId;
            this.size = size;
            this.checksum = checksum;
        }

        public String getContentId() { return contentId; }
        public long getSize() { return size; }
        public String getChecksum() { return checksum; }
    }

    public StoredContent putIfAbsent(Path partsDir, int totalParts, long expectedSize, String expectedChecksum)
            throws IOException {
        return null;
    }

    public ReadableByteChannel openChannel(String contentId) throws IOException {
        return null;
    }

    public InputStream openStream(String contentId) throws IOException {
        return null;
    }

    public Path blobPath(String contentId) {
        return null;
    }
}
