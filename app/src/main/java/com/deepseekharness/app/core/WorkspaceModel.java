package com.deepseekharness.app.core;

import java.io.Serializable;

/**
 * 工作区数据模型，对应 /root/.dsh/storages/workspace.json 中的注册项。
 */
public class WorkspaceModel implements Serializable {
    public final String id;
    public final String title;
    public final String path;
    public final int sessionCount;
    public final boolean isCurrent;
    public final boolean supportsSymlink;
    public final String createdAt;
    public final String updatedAt;

    public WorkspaceModel(String id, String title, String path, int sessionCount,
                          boolean isCurrent, boolean supportsSymlink,
                          String createdAt, String updatedAt) {
        this.id = id != null ? id : "";
        this.title = title != null ? title : "";
        this.path = path != null ? path : "";
        this.sessionCount = Math.max(0, sessionCount);
        this.isCurrent = isCurrent;
        this.supportsSymlink = supportsSymlink;
        this.createdAt = createdAt != null ? createdAt : "";
        this.updatedAt = updatedAt != null ? updatedAt : "";
    }

    public String getDisplayType() {
        return supportsSymlink ? "原生 Linux (支持软链接)" : "手机共享存储";
    }
}
