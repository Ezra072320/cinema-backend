package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.common.util.AliOssUtil;
import edu.czu.cinema.configproperties.AliOssProperties;
import edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.service.MovieMetadataPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 图片必须成功转存后才允许写入业务库。稳定 key 保证重复更新不重复创建 OSS 对象。 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AliOssProperties.class)
public class MovieMediaStore {
    private final MaoyanClient source;
    private final AliOssUtil oss;
    private final ObjectMapper json;
    private final TmdbClient tmdb;

    public MainlandMovieFetcher.Fetched prepare(MainlandMovieFetcher.Fetched fetched) {
        MaoyanMovieSnapshot snapshot = fetched.snapshot();
        Map<String,Object> fields = json.convertValue(snapshot, new TypeReference<>() {});
        Map<String,String> transferred = new HashMap<>();
        String provider = "TMDB".equals(snapshot.source()) ? "tmdb" : "maoyan";
        fields.put("img", transfer(snapshot.img(), "posters", snapshot.id(), provider, transferred));
        List<String> warnings = new ArrayList<>(fetched.warnings());
        List<String> photos = new ArrayList<>();
        try {
            if (snapshot.bgImg() != null) fields.put("bgImg", transfer(snapshot.bgImg(), "backdrops", snapshot.id(), provider, transferred));
            if (snapshot.photos() != null) for (String photo : snapshot.photos()) {
                photos.add(transfer(photo, "stills", snapshot.id(), provider, transferred));
            }
            fields.put("photos", photos);
        } catch (RuntimeException ex) {
            fields.put("bgImg", null); fields.put("photos", null);
            warnings.add("剧照暂未转存，保留原剧照");
        }
        return new MainlandMovieFetcher.Fetched(json.convertValue(fields, MaoyanMovieSnapshot.class), fetched.credits(), List.copyOf(warnings),fetched.tmdbId(),fetched.maoyanId());
    }

    public boolean isStored(String url) { return url != null && url.startsWith(oss.publicUrl("")); }

    public MainlandMovieFetcher.Fetched prepareRepair(MainlandMovieFetcher.Fetched fetched, Movie movie) {
        var filtered = MovieMetadataPolicy.filter(movie,fetched.snapshot(),true);
        Map<String,Object> fields = json.convertValue(filtered,new TypeReference<>() {});
        // 不缺海报时不下载来源图片，文字资料补缺也不依赖来源提供新海报。
        if (filtered.img() == null) fields.put("img", movie.getPosterUrl());
        return prepare(new MainlandMovieFetcher.Fetched(json.convertValue(fields,MaoyanMovieSnapshot.class),
                List.of(),fetched.warnings(),fetched.tmdbId(),fetched.maoyanId()));
    }

    public String transferPoster(String url, Integer movieId) {
        return transfer(url, "posters", movieId, "maoyan", new HashMap<>());
    }

    private String transfer(String url, String kind, Integer movieId, String provider, Map<String,String> transferred) {
        if (isStored(url)) return url;
        if (url == null || url.isBlank()) throw new IllegalStateException("海报地址缺失，未写入数据库");
        if (transferred.containsKey(url)) return transferred.get(url);
        String key = "cinema/movies/" + kind + "/" + provider + "-" + movieId + "-" + hash(url) + ".image";
        // 先查询已转存对象，避免重复同步不断下载、上传相同图片。
        if (oss.objectExists(key)) {
            String stored = oss.publicUrl(key);
            transferred.put(url, stored);
            return stored;
        }
        MaoyanClient.Picture picture = "image.tmdb.org".equals(java.net.URI.create(url).getHost())
                ? tmdb.downloadPicture(url) : source.downloadPicture(url);
        String stored = oss.uploadBytes(picture.bytes(), key, picture.contentType());
        if (!isStored(stored)) throw new IllegalStateException("OSS 未返回预期的海报地址");
        transferred.put(url, stored);
        return stored;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,20); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
