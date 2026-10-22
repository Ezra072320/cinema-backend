package edu.czu.cinema.domain.movie.sync;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MainlandMovieFetcher {
    private final MaoyanClient client;
    private final ObjectMapper json;
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public record Credit(Integer externalId, String name, String avatar, int roleType, String character, Integer tmdbId) {
        public Credit(Integer externalId, String name, String avatar, int roleType, String character) {
            this(externalId,name,avatar,roleType,character,null);
        }
    }
    public record Fetched(MaoyanMovieSnapshot snapshot, List<Credit> credits, List<String> warnings, Integer tmdbId, Integer maoyanId) {
        public Fetched(MaoyanMovieSnapshot snapshot, List<Credit> credits, List<String> warnings) {
            this(snapshot,credits,warnings,snapshot != null && "TMDB".equals(snapshot.source()) ? snapshot.id() : null,
                    snapshot != null && "MAOYAN".equals(snapshot.source()) ? snapshot.id() : null);
        }
    }

    public Fetched fetch(MaoyanClient.Candidate candidate) {
        JsonNode detail = client.detail(candidate.id());
        List<String> warnings = new ArrayList<>();
        JsonNode credits;
        try { credits = client.crew(candidate.id()); }
        catch (MaoyanClient.SourceUnavailableException ex) { throw ex; }
        catch (RuntimeException ex) {
            credits = json.createArrayNode();
            warnings.add("演职员暂未更新");
        }
        return parse(candidate, detail, credits, warnings);
    }

    Fetched parse(MaoyanClient.Candidate candidate, JsonNode detail, JsonNode groups, List<String> warnings) {
        if (detail.path("id").asInt() != candidate.id() || detail.path("nm").asText("").isBlank()) {
            throw new IllegalArgumentException("影片详情无效或 ID 不匹配");
        }
        List<Credit> credits = new ArrayList<>();
        int actors = 0;
        for (JsonNode group : groups) for (JsonNode person : group) {
            int role = switch (person.path("cr").asInt()) { case 1 -> 1; case 2 -> 2; case 4 -> 3; default -> 0; };
            String name = text(person, "cnm");
            if (role == 0 || name == null || (role == 1 && actors++ >= 12)) continue;
            credits.add(new Credit(person.path("id").asInt() > 0 ? person.path("id").asInt() : null,
                    name, url(text(person, "avatar")), role, text(person, "roles")));
        }
        LocalDate release = date(candidate.releaseDate());
        if (release == null) release = date(text(detail, "rt"));
        if (release == null) throw new IllegalArgumentException("未公布明确的大陆上映日，暂不导入：" + candidate.title());
        String poster = url(text(detail, "img"));
        if (poster == null) throw new IllegalArgumentException("影片海报缺失，未导入：" + candidate.title());
        List<String> photos = new ArrayList<>();
        for (JsonNode photo : detail.path("photos")) {
            String value = url(photo.asText(null));
            if (value != null && !value.equals(poster) && !photos.contains(value)) photos.add(value);
            if (photos.size() == 5) break;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", candidate.id());
        fields.put("nm", text(detail, "nm"));
        fields.put("enm", text(detail, "enm"));
        fields.put("cat", text(detail, "cat"));
        String directors = credits.stream().filter(c -> c.roleType() == 2).map(Credit::name).distinct().collect(Collectors.joining(","));
        fields.put("dir", StrUtil.blankToDefault(directors, text(detail, "dir")));
        String cast = credits.stream().filter(c -> c.roleType() == 1)
                .map(c -> c.name() + (c.character() == null ? "" : " 饰 " + c.character())).collect(Collectors.joining(","));
        fields.put("star", StrUtil.blankToDefault(cast, text(detail, "star")));
        String summary = text(detail, "dra");
        fields.put("story", plainText(summary));
        fields.put("dur", detail.path("dur").asInt() > 0 ? detail.path("dur").asInt() : null);
        fields.put("sc", detail.path("sc").asDouble() > 0 ? detail.path("sc").decimalValue() : null);
        fields.put("snum", detail.has("snum") ? detail.path("snum").asInt() : null);
        fields.put("wishCnt", detail.has("wish") ? detail.path("wish").asInt() : null);
        fields.put("img", poster);
        fields.put("bgImg", photos.isEmpty() ? null : photos.getFirst());
        fields.put("photos", photos);
        fields.put("videoUrl", url(StrUtil.blankToDefault(text(detail, "videourl"), text(detail, "vd"))));
        fields.put("rt", release.toString());
        fields.put("src", text(detail, "src")); // 产地和上映市场不同，进口电影也应进入片单。
        String format = text(detail, "ver");
        fields.put("fmt", format == null ? null : format.substring(0, Math.min(32, format.length())));
        fields.put("language", text(detail, "oriLang"));
        fields.put("source", "MAOYAN");
        fields.put("status", release.isAfter(LocalDate.now()) ? 2 : 0);
        String alias = text(detail, "filmAlias");
        if (alias != null) fields.put("aka", List.of(alias));
        return new Fetched(json.convertValue(fields, MaoyanMovieSnapshot.class), List.copyOf(credits), List.copyOf(warnings));
    }

    static LocalDate date(String value) {
        if (value == null) return null;
        var matcher = DATE.matcher(value);
        if (!matcher.find()) return null;
        try { return LocalDate.parse(matcher.group()); } catch (RuntimeException ex) { return null; }
    }
    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    /**
     * 把 HTML 简介清洗成纯文本。先 unescape 再清标签，循环到稳定：
     * 旧的 clean(unescape) 顺序会先把实体编码的标签原样留下，unescape 后又还原成真标签入库（XSS/样式注入面）。
     * 双重编码场景下多跑一轮，直至不再变化。
     */
    /** script/style 整块（含内容）必须一起删——只删标签会把 alert(1) 之类的脚本正文留在简介里. */
    private static final java.util.regex.Pattern SCRIPT_STYLE_BLOCK =
            java.util.regex.Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");

    static String plainText(String value) {
        if (value == null) return null;
        String current = value;
        for (int i = 0; i < 4; i++) { // 上限 4 轮：正常文本 1 轮即稳定，双重编码再补 1 轮。
            String next = SCRIPT_STYLE_BLOCK.matcher(HtmlUtil.unescape(current)).replaceAll("");
            next = HtmlUtil.cleanHtmlTag(next);
            String collapsed = next.replaceAll("[\\t\\n\\r]+", " ").replaceAll(" {2,}", " ").trim();
            if (collapsed.equals(current)) return collapsed;
            current = collapsed;
        }
        return current;
    }

    private static String url(String value) { return urlPublic(value); }

    /** 图片地址清洗：仅放行 http(s)，img 尺寸段 w.h/ 只替换第一处，http 升级 https。供单测与解析复用。 */
    static String urlPublic(String value) {
        if (value == null) return null;
        try {
            // 猫眼图床尺寸占位段 "w.h/" 只替换开头的这一处，避免全局 replace 误伤名里恰含该串的路径。
            String normalized = value.replaceFirst("^w\\.h/", "").replaceFirst("w\\.h/", "");
            URI uri = URI.create(normalized);
            // 只放行 https；http 时按图床能力升级为 https，避免入库 http 混合内容。
            String scheme = uri.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
            if (uri.getHost() == null) return null;
            return ("https".equals(scheme) ? uri.toString()
                    : "https://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath())
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (RuntimeException ex) { return null; }
    }
}
