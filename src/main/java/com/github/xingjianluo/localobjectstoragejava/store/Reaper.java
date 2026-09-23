package com.github.xingjianluo.localobjectstoragejava.store;

/**
 * 资源回收：过期上传会话 + 引用计数为 0 且超过宽限期的 blob。
 */
@org.springframework.stereotype.Component
public class Reaper {

    public int reapExpiredSessions(long nowMillis) {
        return 0;
    }

    public int reapUnreachableBlobs(long nowMillis) {
        return 0;
    }
}
