package com.ftysync.core;

/**
 * 清单解析/校验失败。
 *
 * <p>属于「服务端给了坏数据」或「本地状态文件损坏」一类的问题，调用方应降级处理
 * （例如拒绝同步并提示），而不是让游戏崩溃。</p>
 */
public class ManifestException extends RuntimeException {

    public ManifestException(String message) {
        super(message);
    }

    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
