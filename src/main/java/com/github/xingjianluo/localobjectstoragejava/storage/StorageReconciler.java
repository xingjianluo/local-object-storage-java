package com.github.xingjianluo.localobjectstoragejava.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动对账：进程在写入/合并/删除中途被强杀后，重启时把存储对清楚。
 *
 * <p>职责（详见 README）：</p>
 * <ul>
 *   <li>以版本链元数据为准重建引用计数，修正崩溃造成的计数漂移；</li>
 *   <li>删除不被任何版本引用的残留内容（含引用计数文件），不让其永远占盘；</li>
 *   <li>清理 tmp 目录中写入中断遗留的临时文件；</li>
 *   <li>对元数据引用但底层内容缺失的情况明确报错（STORAGE_CORRUPT 级别日志），不静默。</li>
 * </ul>
 */
@Component
public class StorageReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciler.class);

    private final LocalObjectStorageService objectStorageService;
    private final LocalContentStore contentStore;

    public StorageReconciler(LocalObjectStorageService objectStorageService,
                             LocalContentStore contentStore) {
        this.objectStorageService = objectStorageService;
        this.contentStore = contentStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 以版本链元数据为准对账：元数据可见 ⇒ 内容必可达；元数据不引用 ⇒ 可安全回收
        var expectedRefs = objectStorageService.contentReferences();
        ContentStore.ReconcileReport report = contentStore.reconcile(expectedRefs);
        if (!report.missingContents().isEmpty()) {
            log.error("对账发现 {} 个被引用但缺失的内容（STORAGE_CORRUPT 级别），需人工介入: {}",
                    report.missingContents().size(), report.missingContents());
        }
        log.info("存储对账完成：引用 {} 条，修正 {} 条，回收残留 {} 个，清理临时文件 {} 个",
                expectedRefs.size(), report.correctedRefCounts().size(),
                report.removedOrphans().size(), report.removedTmpFiles());
    }
}
