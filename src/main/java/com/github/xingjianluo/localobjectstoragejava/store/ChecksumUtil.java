package com.github.xingjianluo.localobjectstoragejava.store;

import java.io.IOException;
import java.io.InputStream;

/**
 * 内容校验工具：SHA-256，支持流式计算，避免大对象全量进内存。
 */
public final class ChecksumUtil {

    private ChecksumUtil() {
    }

    public static String sha256Hex(InputStream in) throws IOException {
        return null;
    }

    public static String sha256Hex(byte[] data) {
        return null;
    }
}
