package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.config.MovieSyncProperties;
import edu.czu.cinema.config.TmdbProperties;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** TMDB 只补有据可查的资料，备用片单必须逐片核对 CN 院线上映记录。 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties({TmdbProperties.class,MovieSyncProperties.class})
public class TmdbMovieService {
    private final TmdbClient client;
    private final ObjectMapper json;
    private final TmdbProperties properties;
    private final MovieSyncProperties syncProperties;

    public boolean configured() { return client.configured(); }

    public List<MovieSyncCandidate> discover() {
        LocalDate from = LocalDate.now().plusDays(1); // 只收未来待映
        LocalDate until = LocalDate.now().plusDays(syncProperties.getUpcomingDays());
        Map<Integer,MovieSyncCandidate> movies = new LinkedHashMap<>();
        int pages = 1;
        for (int page=1; page<=pages; page++) {
            JsonNode result = client.get("/discover/movie",Map.of("region","CN","with_release_type","3|2",
                    "release_date.gte",from.toString(),"release_date.lte",until.toString(),
                    "include_adult","false","sort_by","release_date.desc","page",String.valueOf(page)));
            if (!result.path("results").isArray() || result.path("page").asInt() != page)
                throw new IllegalStateException("TMDB 片单分页无效，保留已有资料");
            pages = result.path("total_pages").asInt();
            if (pages > 500) throw new IllegalStateException("TMDB 片单超过分页上限，请缩小近期日期范围");
            for (JsonNode row : result.path("results")) {
                if (row.path("id").asInt() > 0 && !row.path("adult").asBoolean()
                        && text(row,"title")!=null) {
                    movies.put(row.path("id").asInt(),new MovieSyncCandidate("TMDB",row.path("id").asInt(),
                            text(row,"title"),text(row,"release_date")));
                }
            }
        }
        if (movies.isEmpty()) throw new IllegalStateException("TMDB 未返回有效的大陆待映候选，保留已有资料");
        return List.copyOf(movies.values());
    }

    public MainlandMovieFetcher.Fetched fetch(MovieSyncCandidate candidate) {
        JsonNode detail = details(candidate.id());
        LocalDate from = LocalDate.now().plusDays(1); // 只收未来待映
        LocalDate until = LocalDate.now().plusDays(syncProperties.getUpcomingDays());
        // 列表日期可能是全球首映日，不能据此排除老片在大陆的新上映或重映。
        List<LocalDate> dates = mainlandDates(detail).stream().filter(d -> !d.isBefore(from) && !d.isAfter(until)).toList();
        LocalDate release = dates.stream().max(Comparator.naturalOrder()).orElseThrow(() ->
                new IllegalArgumentException("TMDB 未提供日期范围内的大陆院线上映记录："+candidate.title()));
        return parse(new MovieSyncCandidate("TMDB",candidate.id(),candidate.title(),release.toString()),detail);
    }

    /** 已有可信 TMDB ID 的历史影片不受近期日期窗口限制，也不因来源暂无海报而阻断文字补缺。 */
    public MainlandMovieFetcher.Fetched fetchExisting(Movie movie) {
        if (movie.getTmdbId() == null) throw new IllegalArgumentException("影片尚未关联 TMDB ID");
        return parse(new MovieSyncCandidate("TMDB",movie.getTmdbId(),movie.getTitle(),
                movie.getReleaseDate() == null ? null : movie.getReleaseDate().toString()),details(movie.getTmdbId()),false);
    }

    /** 已匹配过优先用独立 TMDB ID；首次匹配必须片名与大陆上映日期一致且结果唯一。 */
    public Optional<MainlandMovieFetcher.Fetched> match(MaoyanClient.Candidate candidate,
            MainlandMovieFetcher.Fetched primary, Movie previous) {
        return match(candidate,primary,previous,false);
    }

    public Optional<MainlandMovieFetcher.Fetched> matchForRepair(Movie movie, MainlandMovieFetcher.Fetched primary) {
        return match(new MaoyanClient.Candidate(movie.getMaoyanId() == null ? 0 : movie.getMaoyanId(),movie.getTitle(),
                movie.getReleaseDate() == null ? null : movie.getReleaseDate().toString()),primary,movie,true);
    }

    private Optional<MainlandMovieFetcher.Fetched> match(MaoyanClient.Candidate candidate,
            MainlandMovieFetcher.Fetched primary, Movie previous, boolean repair) {
        if (!configured()) return Optional.empty();
        if (previous != null && previous.getTmdbId() != null)
            return Optional.of(repair ? fetchExisting(previous) : parse(new MovieSyncCandidate("TMDB",previous.getTmdbId(),candidate.title(),candidate.releaseDate()),details(previous.getTmdbId())));
        JsonNode search = client.get("/search/movie",Map.of("query",candidate.title(),"include_adult","false","page","1"));
        if (!search.path("results").isArray()) throw new IllegalStateException("TMDB 搜索结果无效");
        String original = primary != null && primary.snapshot().enm() != null ? primary.snapshot().enm() : previous == null ? null : previous.getOriginalTitle();
        List<JsonNode> possible = new ArrayList<>();
        for (JsonNode row : search.path("results")) {
            if (!row.path("adult").asBoolean() && (sameTitle(candidate.title(),text(row,"title"))
                    || (original != null && sameTitle(original,text(row,"original_title"))))) possible.add(row);
        }
        // 结果过多说明名称有歧义，不为寻找一个“看起来像”的结果遍历全库。
        if (possible.size() > 3) return Optional.empty();
        List<MainlandMovieFetcher.Fetched> matches = new ArrayList<>();
        LocalDate expected = MainlandMovieFetcher.date(candidate.releaseDate());
        for (JsonNode row : possible) {
            JsonNode detail = details(row.path("id").asInt());
            LocalDate mainland = mainlandRelease(detail,expected);
            if (expected == null || mainland == null || !expected.equals(mainland)) continue;
            if (!sameTitle(candidate.title(),text(detail,"title"))
                    && !(original != null && sameTitle(original,text(detail,"original_title")))) continue;
            matches.add(parse(new MovieSyncCandidate("TMDB",row.path("id").asInt(),candidate.title(),candidate.releaseDate()),detail,!repair));
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public MainlandMovieFetcher.Fetched supplement(MainlandMovieFetcher.Fetched primary, MainlandMovieFetcher.Fetched extra) {
        Map<String,Object> merged = json.convertValue(primary.snapshot(),new TypeReference<>() {});
        Map<String,Object> additions = json.convertValue(extra.snapshot(),new TypeReference<>() {});
        for (String field : List.of("enm","cat","dir","star","story","dur","img","bgImg","photos","language","src")) {
            Object value = merged.get(field);
            if (value == null || (value instanceof String s && s.isBlank()) || (value instanceof List<?> l && l.isEmpty()))
                merged.put(field,additions.get(field));
        }
        List<MainlandMovieFetcher.Credit> credits = new ArrayList<>(primary.credits());
        Set<Integer> knownRoles = new HashSet<>();
        for (var credit : primary.credits()) knownRoles.add(credit.roleType());
        for (var credit : extra.credits()) if (!knownRoles.contains(credit.roleType())) credits.add(credit);
        return new MainlandMovieFetcher.Fetched(json.convertValue(merged,MaoyanMovieSnapshot.class),List.copyOf(credits),
                primary.warnings(),extra.tmdbId(),primary.maoyanId());
    }

    public MainlandMovieFetcher.Fetched fallback(MaoyanClient.Candidate candidate,MainlandMovieFetcher.Fetched extra) {
        Map<String,Object> fields = json.convertValue(extra.snapshot(),new TypeReference<>() {});
        fields.put("nm",candidate.title());
        fields.put("status",MainlandMovieFetcher.date(candidate.releaseDate()).isAfter(LocalDate.now()) ? 2 : 0);
        return new MainlandMovieFetcher.Fetched(json.convertValue(fields,MaoyanMovieSnapshot.class),extra.credits(),
                extra.warnings(),extra.tmdbId(),candidate.id());
    }

    static boolean needsSupplement(MainlandMovieFetcher.Fetched primary) {
        var s = primary.snapshot();
        return s.dur() == null || s.dur() <= 0 || s.story() == null || s.story().isBlank()
                || s.dir() == null || s.dir().isBlank() || s.language() == null || s.language().isBlank()
                || s.cat() == null || s.cat().isBlank() || s.src() == null || s.src().isBlank();
    }

    private JsonNode details(int id) {
        return client.get("/movie/" + id,Map.of("append_to_response","credits,release_dates,images",
                "include_image_language","zh,en,null"));
    }

    MainlandMovieFetcher.Fetched parse(MovieSyncCandidate candidate, JsonNode detail) {
        return parse(candidate,detail,true);
    }

    private MainlandMovieFetcher.Fetched parse(MovieSyncCandidate candidate, JsonNode detail, boolean strict) {
        if (detail.path("id").asInt() != candidate.id() || text(detail,"title") == null || detail.path("adult").asBoolean())
            throw new IllegalArgumentException("TMDB 影片详情无效");
        LocalDate release = mainlandRelease(detail,MainlandMovieFetcher.date(candidate.releaseDate()));
        LocalDate expected = MainlandMovieFetcher.date(candidate.releaseDate());
        if (strict && (release == null || (expected != null && !expected.equals(release))))
            throw new IllegalArgumentException("TMDB 尚无与候选日期一致的中国大陆院线上映记录，未导入：" + candidate.title());
        String poster = text(detail,"poster_path");
        for (JsonNode image : detail.path("images").path("posters")) {
            if ("zh".equals(image.path("iso_639_1").asText()) && text(image,"file_path")!=null) {
                poster=text(image,"file_path"); break;
            }
        }
        if (poster == null) for (JsonNode image : detail.path("images").path("posters")) {
            poster = text(image,"file_path"); if (poster != null) break;
        }
        if (strict && poster == null) throw new IllegalArgumentException("TMDB 影片海报缺失，未导入");
        List<MainlandMovieFetcher.Credit> credits = new ArrayList<>();
        for (JsonNode person : detail.path("credits").path("crew")) {
            String job = text(person,"job");
            int role = "Director".equals(job) ? 2 : Set.of("Writer","Screenplay","Story").contains(job == null ? "" : job) ? 3 : 0;
            if (role > 0) addCredit(credits,person,role,null);
        }
        int actors = 0;
        for (JsonNode person : detail.path("credits").path("cast")) {
            addCredit(credits,person,1,text(person,"character"));
            if (++actors >= 12) break;
        }
        List<String> genres = new ArrayList<>();
        for (JsonNode genre : detail.path("genres")) if (text(genre,"name") != null) genres.add(text(genre,"name"));
        List<String> countries = new ArrayList<>();
        for (JsonNode country : detail.path("production_countries")) {
            String code = text(country,"iso_3166_1");
            String name = code == null ? text(country,"name") : java.util.Locale.of("",code).getDisplayCountry(java.util.Locale.SIMPLIFIED_CHINESE);
            if (name!=null) countries.add(name);
        }
        List<String> photos = new ArrayList<>();
        for (JsonNode photo : detail.path("images").path("backdrops")) {
            String url = image(text(photo,"file_path"),"w1280");
            if (url != null && !photos.contains(url)) photos.add(url);
            if (photos.size() >= 5) break;
        }
        Map<String,Object> fields = new LinkedHashMap<>();
        fields.put("id",candidate.id()); fields.put("source","TMDB"); fields.put("nm",text(detail,"title"));
        fields.put("enm",text(detail,"original_title"));
        // overview 同样走「先 unescape 再刷标签」的纯文本清洗（与 MainlandMovieFetcher 同口径）。
        fields.put("story", MainlandMovieFetcher.plainText(text(detail,"overview")));
        fields.put("dur",detail.path("runtime").asInt() > 0 ? detail.path("runtime").asInt() : null);
        fields.put("cat",String.join(",",genres)); fields.put("src",String.join(",",countries));
        fields.put("dir",credits.stream().filter(c -> c.roleType()==2).map(MainlandMovieFetcher.Credit::name).distinct().collect(java.util.stream.Collectors.joining(",")));
        fields.put("star",credits.stream().filter(c -> c.roleType()==1).map(c -> c.name() + (c.character()==null ? "" : " 饰 " + c.character())).collect(java.util.stream.Collectors.joining(",")));
        String language = text(detail,"original_language");
        fields.put("language",language == null ? null : java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE));
        fields.put("rt",release == null ? null : release.toString());
        fields.put("status",release == null ? null : release.isAfter(LocalDate.now()) ? 2 : 0); // 日期不等于仍在全国热映。
        fields.put("img",image(poster,"w500")); fields.put("bgImg",image(text(detail,"backdrop_path"),"w1280"));
        fields.put("photos",photos);
        if (detail.path("vote_count").asInt()>0 && detail.path("vote_average").asDouble()>0) {
            fields.put("sc",detail.path("vote_average").decimalValue()); fields.put("snum",detail.path("vote_count").asInt());
        }
        return new MainlandMovieFetcher.Fetched(json.convertValue(fields,MaoyanMovieSnapshot.class),List.copyOf(credits),List.of());
    }

    static LocalDate mainlandRelease(JsonNode detail, LocalDate expected) {
        List<LocalDate> dates = mainlandDates(detail);
        if (expected != null && dates.contains(expected)) return expected;
        return dates.stream().min(expected == null ? Comparator.naturalOrder()
                : Comparator.comparingLong(d -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(expected,d)))).orElse(null);
    }

    private static List<LocalDate> mainlandDates(JsonNode detail) {
        List<LocalDate> dates = new ArrayList<>();
        for (JsonNode country : detail.path("release_dates").path("results")) {
            if (!"CN".equals(country.path("iso_3166_1").asText())) continue;
            for (JsonNode row : country.path("release_dates")) {
                int type = row.path("type").asInt();
                LocalDate date = MainlandMovieFetcher.date(text(row,"release_date"));
                if ((type == 2 || type == 3) && date != null) dates.add(date);
            }
        }
        return dates;
    }

    private static void addCredit(List<MainlandMovieFetcher.Credit> credits,JsonNode person,int role,String character) {
        String name = text(person,"name");
        if (name != null) credits.add(new MainlandMovieFetcher.Credit(null,name,image(text(person,"profile_path"),"w185"),
                role,character,person.path("id").asInt()>0 ? person.path("id").asInt() : null));
    }
    static boolean sameTitle(String a,String b) {
        return a != null && b != null && a.replaceAll("[\\p{P}\\p{Z}\\s]", "").equalsIgnoreCase(b.replaceAll("[\\p{P}\\p{Z}\\s]", ""));
    }
    private static String image(String path,String size) {
        return path != null && path.matches("/[a-zA-Z0-9._/-]+") ? "https://image.tmdb.org/t/p/" + size + path : null;
    }
    private static String text(JsonNode node,String field) {
        String value = node.path(field).asText("").trim(); return value.isBlank() ? null : value;
    }
}
