package com.game.demo;

import org.mindrot.jbcrypt.BCrypt;

/**
 * 密码哈希工具。
 * <p>
 * 新密码统一用 BCrypt 哈希后存储；为兼容 Demo 早期以明文写入 DB 的老账号，
 * {@link #matches} 在判定失败后会再走一次明文比对，由调用方决定是否回写成哈希。
 */
public class PasswordUtil {

    private static final int LOG_ROUNDS = 12;

    /** 对原始密码做 BCrypt 哈希。 */
    public static String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(LOG_ROUNDS));
    }

    /**
     * 用 BCrypt 校验。
     * @return true=密码正确；false=密码错误
     */
    public static boolean check(String rawPassword, String stored) {
        if (stored == null || stored.isEmpty()) return false;
        try {
            return BCrypt.checkpw(rawPassword, stored);
        } catch (Exception ignored) {
            // stored 不是合法 BCrypt 串（老的明文数据），交给调用方走明文兼容逻辑
            return false;
        }
    }

    /** 判断 DB 里现存的字符串是不是老的明文数据（BCrypt 串以 $2 开头，长度 60）。 */
    public static boolean isLegacyPlainText(String stored) {
        return stored != null && !stored.startsWith("$2");
    }
}
