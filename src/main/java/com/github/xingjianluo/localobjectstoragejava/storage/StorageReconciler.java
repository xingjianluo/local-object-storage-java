package com.github.xingjianluo.localobjectstoragejava.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动对账：进程（可能是在写入/合并中途被强杀后）重启时，
 * 以版本链实际引用为准校正内容存储的引用计数，并立即回收零引用残留。
 *
 * <p>在两个存储组件都完成磁盘状态加载之后、对外提供服务之前执行，
 * 保证：仍被版本引用的内容绝不误删；崩溃窗口产生的无引用残留不会永远占盘。</p>
 */
@Component
public class StorageReconciler {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciler.class);

    public StorageReconciler(ContentStore contentStore, ObjectStorageService objectStorageService) {
        contentStore.reconcile(objectStorageService.contentReferences());
        List<String> swept = contentStore.sweep();
        if (!swept.isEmpty()) {
            log.info("启动对账回收 {} 个无引用内容: {}", swept.size(), swept);
        }
    }
}
