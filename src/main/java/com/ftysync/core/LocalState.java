package com.ftysync.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 客户端本地同步状态（{@code ftyupater/state.json}）。
 *
 * <p>记录的是一次成功同步后的基线：代际、已安装文件哈希、玩家 opt-out/忽略列表。
 * 同步前用它和服务器清单做差异；同步后写回。</p>
 */
public final class LocalState {

    public static final int SCHEMA = 2;

    private int schema = SCHEMA;
    private String generation;
    private String lastSync;
    private Set<String> optOut = new LinkedHashSet<>();
    private Set<String> ignored = new LinkedHashSet<>();
    private Map<String, Installed> entries = new LinkedHashMap<>();

    public int schema() {
        return schema;
    }

    public String generation() {
        return generation;
    }

    public void setGeneration(String generation) {
        this.generation = generation;
    }

    public String lastSync() {
        return lastSync;
    }

    public void setLastSync(String lastSync) {
        this.lastSync = lastSync;
    }

    public Set<String> optOut() {
        return optOut;
    }

    /** 供状态文件反序列化后归一化（null → 空集合）。 */
    public void setOptOut(Set<String> optOut) {
        this.optOut = optOut == null ? new LinkedHashSet<>() : new LinkedHashSet<>(optOut);
    }

    public Set<String> ignored() {
        return ignored;
    }

    /** 供状态文件反序列化后归一化（null → 空集合）。 */
    public void setIgnored(Set<String> ignored) {
        this.ignored = ignored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(ignored);
    }

    /** 已安装文件（path → 哈希/大小），不含 .disabled。 */
    public Map<String, Installed> entries() {
        return entries;
    }

    /** 供状态文件反序列化后归一化（null → 空表）。 */
    public void setEntries(Map<String, Installed> entries) {
        this.entries = entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);
    }

    public void markInstalled(String path, String sha256, long size) {
        entries.put(PathGuard.normalize(path), new Installed(sha256, size));
    }

    public void removeInstalled(String path) {
        entries.remove(path);
    }

    public boolean isOptOut(String path) {
        return optOut.contains(path);
    }

    /** 一个已安装文件的记录。 */
    public record Installed(String sha256, long size) {
        public Installed {
            sha256 = Hash.normalize(sha256);
            if (size < 0) {
                throw new IllegalArgumentException("文件大小不能为负: " + size);
            }
        }
    }
}
