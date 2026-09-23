package com.github.xingjianluo.localobjectstoragejava.store;

import com.github.xingjianluo.localobjectstoragejava.model.ErrorCode;
import com.github.xingjianluo.localobjectstoragejava.model.StorageException;

import java.io.IOException;
import java.io.InputStream;

/**
 * 限定最多读取 limit 字节的输入流。
 *
 * <p>若上游数据超过声明字节数，继续 read 时抛出带 PART_SIZE_MISMATCH 的异常，
 * 避免伪造的分片大小声明把磁盘写满；数据不足则正常返回 -1（由大小比对发现）。</p>
 */
public class BoundedInputStream extends InputStream {

    private final InputStream in;
    private final long limit;
    private long consumed;

    public BoundedInputStream(InputStream in, long limit) {
        this.in = in;
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        if (consumed >= limit) {
            int next = in.read();
            if (next != -1) {
                throw new StorageException(ErrorCode.PART_SIZE_MISMATCH,
                        "分片数据超过声明大小 " + limit);
            }
            return -1;
        }
        int b = in.read();
        if (b != -1) {
            consumed++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (consumed >= limit) {
            // 探测一个字节判断是否真的还有多余数据。
            int extra = in.read();
            if (extra != -1) {
                throw new StorageException(ErrorCode.PART_SIZE_MISMATCH,
                        "分片数据超过声明大小 " + limit);
            }
            return -1;
        }
        int max = (int) Math.min(len, limit - consumed);
        int read = in.read(b, off, max);
        if (read > 0) {
            consumed += read;
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
