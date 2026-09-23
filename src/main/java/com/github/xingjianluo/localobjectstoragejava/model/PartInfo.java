package com.github.xingjianluo.localobjectstoragejava.model;

import java.util.Objects;

/**
 * 分片元数据：编号、字节数、SHA-256 校验值与落盘时间。
 * 可被 Jackson 直接序列化/反序列化。
 */
public class PartInfo {

    private int number;
    private long size;
    private String checksum;
    private long uploadedAtMillis;

    public PartInfo() {
    }

    public PartInfo(int number, long size, String checksum, long uploadedAtMillis) {
        this.number = number;
        this.size = size;
        this.checksum = checksum;
        this.uploadedAtMillis = uploadedAtMillis;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
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

    public long getUploadedAtMillis() {
        return uploadedAtMillis;
    }

    public void setUploadedAtMillis(long uploadedAtMillis) {
        this.uploadedAtMillis = uploadedAtMillis;
    }

    /**
     * 同编号分片重复上传时，用 size+checksum 判断是否为同一份重试。
     */
    public boolean sameContent(long otherSize, String otherChecksum) {
        return size == otherSize && Objects.equals(checksum, otherChecksum);
    }
}
