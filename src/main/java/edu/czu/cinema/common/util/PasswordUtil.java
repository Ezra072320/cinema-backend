package edu.czu.cinema.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 密码工具.
 * <p>
 * 新口令一律 BCrypt；历史口令为 MD5(明文 + 固定 salt)，仅保留校验能力用于
 * 登录时惰性升级（on-login rehash）：旧哈希校验通过后由调用方用 {@link #encode}
 * 重算并写回，老用户零感知迁移。不要用 {@link #md5} 写入新口令。
 * </p>
 */
public final class PasswordUtil {

    /** 历史固定 salt —— 仅供旧哈希校验，禁止用于新口令. */
    private static final String LEGACY_SALT = "cinema*renovate*2026";

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private PasswordUtil() {
    }

    /** 新口令编码：BCrypt（随机盐）. */
    public static String encode(String raw) {
        if (raw == null) {
            return null;
        }
        return BCRYPT.encode(raw);
    }

    /** 旧算法编码 —— 仅供迁移期校验旧哈希，禁止写入新口令. */
    public static String md5(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((raw + LEGACY_SALT).getBytes());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /**
     * 校验口令：自动识别 BCrypt 与历史 MD5 两种哈希.
     */
    public static boolean matches(String raw, String hashed) {
        if (raw == null || hashed == null) {
            return false;
        }
        if (isBcrypt(hashed)) {
            return BCRYPT.matches(raw, hashed);
        }
        return hashed.equalsIgnoreCase(md5(raw));
    }

    /** 哈希是否为旧算法 —— true 表示登录成功后应重算升级. */
    public static boolean needsUpgrade(String hashed) {
        return hashed != null && !isBcrypt(hashed);
    }

    /**
     * 口令版本指纹（取哈希前 12 位），嵌入 JWT 的 {@code pv} 声明；
     * 改密后指纹变化，旧令牌在过滤器复核时被拒绝。
     */
    public static String version(String hashed) {
        if (hashed == null || hashed.length() < 12) {
            return String.valueOf(hashed == null ? 0 : hashed.hashCode());
        }
        return hashed.substring(0, 12);
    }

    private static boolean isBcrypt(String hashed) {
        return hashed.startsWith("$2a$") || hashed.startsWith("$2b$") || hashed.startsWith("$2y$");
    }
}
