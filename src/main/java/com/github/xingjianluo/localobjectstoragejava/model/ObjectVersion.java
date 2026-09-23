package com.github.xingjianluo.localobjectstoragejava.model;

/**
 * 对象的一个版本。
 *
 * <p>版本链只追加、不原地改写：删除即把 deleted 置 true（保留审计记录）；
 * 回滚 = 追加一个指向历史内容的新版本（通过 contentId 去重）。</p>
 */
public class ObjectVersion {

    private String versionId;
    private String objectKey;
    private String contentId;
    private long size;
    private String checksum;
    private long createdAtMillis;
    private boolean deleted;
    private long deletedAtMillis;
    private String originVersionId;

    public ObjectVersion() {
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public void setCreatedAtMillis(long createdAtMillis) {
        this.createdAtMillis = createdAtMillis;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public long getDeletedAtMillis() {
        return deletedAtMillis;
    }

    public void setDeletedAtMillis(long deletedAtMillis) {
        this.deletedAtMillis = deletedAtMillis;
    }

    /**
     * 回滚产生的新版本由此字段记录它来源于哪个历史版本，便于审计。
     */
    public String getOriginVersionId() {
        return originVersionId;
    }

    public void setOriginVersionId(String originVersionId) {
        this.originVersionId = originVersionId;
    }
}
