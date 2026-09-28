package com.github.xingjianluo.localobjectstoragejava.storage;

/**
 * 客户端请求的字节范围（写法层面，尚未对照对象长度）。
 *
 * <p>三种合法写法（对应 RFC 7233 单段语法）：</p>
 * <ul>
 *   <li>{@code bytes=start-end}：闭区间，要求 {@code 0 <= start <= end} —— {@link #closed(long, long)}</li>
 *   <li>{@code bytes=start-}：从 start 到对象末尾 —— {@link #from(long)}</li>
 *   <li>{@code bytes=-suffix}：最后 suffix 字节，要求 {@code suffix > 0} —— {@link #suffix(long)}</li>
 * </ul>
 *
 * <p>本类型只负责“写法是否合法”：负数、零/负长度、起止颠倒在构造/解析时直接以
 * {@link StorageException.ErrorCode#INVALID_RANGE} 拒绝；而“写法合法但起点超过对象长度”
 * 由 {@link ObjectStorageService} 在读取时以
 * {@link StorageException.ErrorCode#RANGE_NOT_SATISFIABLE} 拒绝，两类失败稳定可区分。</p>
 */
public sealed interface ByteRange permits ByteRange.Closed, ByteRange.OpenEnded, ByteRange.Suffix {

    /** 闭区间 [start, end]（两端均含），要求 {@code 0 <= start <= end}。 */
    static ByteRange closed(long start, long end) {
        if (start < 0 || end < start) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "非法范围: start=" + start + " end=" + end + "（要求 0 <= start <= end）");
        }
        return new Closed(start, end);
    }

    /** 从 start 到对象末尾，要求 {@code start >= 0}。 */
    static ByteRange from(long start) {
        if (start < 0) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "非法范围: start=" + start + "（不允许负数）");
        }
        return new OpenEnded(start);
    }

    /** 最后 suffix 字节，要求 {@code suffix > 0}。 */
    static ByteRange suffix(long suffix) {
        if (suffix <= 0) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "非法范围: suffix=" + suffix + "（后缀长度必须为正）");
        }
        return new Suffix(suffix);
    }

    /**
     * 解析 {@code Range} 头的单段字节范围（{@code bytes=...}）。
     * 只校验写法，不对照对象长度；写法无法识别时抛 {@link StorageException.ErrorCode#INVALID_RANGE}。
     * 不支持多段（逗号分隔）请求。
     */
    static ByteRange parseHttpHeader(String header) {
        if (header == null) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "Range 头为空");
        }
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, "bytes=".length())) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "无法识别的 Range 头（仅支持 bytes=）: " + header);
        }
        String spec = h.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        // 必须有 '-'，且不支持多段
        if (dash < 0 || spec.indexOf(',') >= 0 || spec.isEmpty()) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "无法识别的 Range 头: " + header);
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        long start;
        long end;
        try {
            if (startPart.isEmpty()) {
                // bytes=-suffix
                return suffix(Long.parseLong(endPart));
            }
            start = Long.parseLong(startPart);
            if (endPart.isEmpty()) {
                // bytes=start-
                return from(start);
            }
            end = Long.parseLong(endPart);
        } catch (NumberFormatException e) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "无法识别的 Range 头（偏移量不是整数）: " + header);
        }
        // bytes=start-end
        return closed(start, end);
    }

    /**
     * 对照对象总长度解析为实际读取区间 {@code [offset, offset+length)}。
     * 请求长度超出对象末尾时自动截断到末尾（不视为错误）。
     * 写法合法但起始位置已达到/超过对象长度时抛
     * {@link StorageException.ErrorCode#RANGE_NOT_SATISFIABLE}。
     */
    Resolved resolve(long totalSize);

    /** 解析后的实际读取区间。 */
    record Resolved(long offset, long length) {}

    record Closed(long start, long end) implements ByteRange {
        @Override
        public Resolved resolve(long totalSize) {
            if (start >= totalSize) {
                throw beyondEnd(start, totalSize);
            }
            return new Resolved(start, Math.min(end - start + 1, totalSize - start));
        }
    }

    record OpenEnded(long start) implements ByteRange {
        @Override
        public Resolved resolve(long totalSize) {
            if (start >= totalSize) {
                throw beyondEnd(start, totalSize);
            }
            return new Resolved(start, totalSize - start);
        }
    }

    record Suffix(long suffixLength) implements ByteRange {
        @Override
        public Resolved resolve(long totalSize) {
            // 空对象没有任何字节可返回
            if (totalSize <= 0) {
                throw beyondEnd(0, totalSize);
            }
            long offset = Math.max(0, totalSize - suffixLength);
            return new Resolved(offset, totalSize - offset);
        }
    }

    private static StorageException beyondEnd(long start, long totalSize) {
        return new StorageException(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE,
                "起始位置 " + start + " 已达到或超过对象长度 " + totalSize);
    }
}
