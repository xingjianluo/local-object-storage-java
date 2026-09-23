package com.github.xingjianluo.localobjectstoragejava.service;

import com.github.xingjianluo.localobjectstoragejava.store.Reaper;
import org.springframework.stereotype.Service;

/**
 * 管理操作：手动触发回收，供验证与测试使用。
 */
@Service
public class StorageAdminService {

    private final Reaper reaper;

    public StorageAdminService(Reaper reaper) {
        this.reaper = reaper;
    }

    public int reapNow() {
        return 0;
    }
}
