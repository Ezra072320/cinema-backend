package edu.czu.cinema.ai.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 客服的座号/座型规范化。
 *
 * <p>模型从聊天里拿到的是“2排2座”“第2排第2号”这类自然语言，而选座接口的标签是
 * {@code 2-2}。之前没有这层转换，模型只能按分页列表“猜”用户说的是哪一排哪一座，
 * 于是会出现用户明确说 2排2座、客服却回答别的座位不可售的情况。
 *
 * <p>只做显示层的格式归一，不改变座位的物理坐标，也不接受任意模糊匹配：
 * 解析失败时返回 null，由工具明确提示用户核对座号，而不是猜一个最接近的座位。
 */
public final class AiSeatLabels {

    private static final Pattern LABEL = Pattern.compile("^(\\d{1,3})[-–—](\\d{1,3})$");

    private AiSeatLabels() {}

    /**
     * 把“2-2”“2排2座”“第2排第2号”“２排２座”统一成 {@code 2-2}。
     *
     * @return 规范化座号；无法识别时返回 null
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        StringBuilder ascii = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            // 全角数字转半角，避免手机输入法把“２排２座”原样送进来
            if (c >= '０' && c <= '９') ascii.append((char) ('0' + (c - '０')));
            else ascii.append(c);
        }
        String value =
                ascii.toString()
                        .strip()
                        .replaceAll("\\s+", "")
                        .replace("第", "")
                        .replace("排", "-")
                        .replace("号", "")
                        .replace("座", "");
        Matcher m = LABEL.matcher(value);
        if (!m.matches()) return null;
        int row = Integer.parseInt(m.group(1));
        int seat = Integer.parseInt(m.group(2));
        if (row < 1 || seat < 1) return null;
        return row + "-" + seat;
    }

    /** 座型名称；与 seat.type 的取值一致。 */
    public static String typeName(Integer type) {
        if (type == null) return "未知座型";
        return switch (type) {
            case 1 -> "标准座";
            case 2 -> "情侣座";
            case 3 -> "VIP座";
            case 4 -> "无障碍座";
            default -> "其他座型(" + type + ")";
        };
    }
}
