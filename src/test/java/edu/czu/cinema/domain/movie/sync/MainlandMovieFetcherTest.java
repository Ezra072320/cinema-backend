package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MainlandMovieFetcherTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MainlandMovieFetcher fetcher = new MainlandMovieFetcher(mock(MaoyanClient.class), json);

    @Test void importsForeignFilmsReleasedInMainlandAndMapsRealCredits() throws Exception {
        var candidate = new MaoyanClient.Candidate(12, "测试影片", "2026-10-01");
        var detail = json.readTree("""
                {"id":12,"nm":"测试影片","src":"美国","dra":"<p>真实简介</p>","dur":120,
                 "sc":8.6,"img":"https://example.com/poster.jpg","oriLang":"英语","cat":"剧情"}
                """);
        var crew = json.readTree("""
                [[{"id":21,"cnm":"导演甲","cr":2}], [{"id":22,"cnm":"演员甲","cr":1,"roles":"角色甲"}],
                 [{"id":23,"cnm":"编剧甲","cr":4}]]
                """);
        var result = fetcher.parse(candidate, detail, crew, List.of());
        assertThat(result.snapshot().src()).isEqualTo("美国");
        assertThat(result.snapshot().story()).isEqualTo("真实简介");
        assertThat(result.snapshot().language()).isEqualTo("英语");
        assertThat(result.credits()).extracting(MainlandMovieFetcher.Credit::roleType).containsExactly(2, 1, 3);
        assertThat(result.snapshot().star()).isEqualTo("演员甲 饰 角色甲");
    }

    @Test void refusesErrorDocumentsAndNeverInventsJanuaryFirstDates() throws Exception {
        var candidate = new MaoyanClient.Candidate(12, "测试影片", "2026");
        assertThatThrownBy(() -> fetcher.parse(candidate, json.readTree("{\"code\":1309}"), json.createArrayNode(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var detail = json.readTree("{\"id\":12,\"nm\":\"测试影片\",\"img\":\"https://example.com/a.jpg\",\"rt\":\"2026\"}");
        assertThatThrownBy(() -> fetcher.parse(candidate, detail, json.createArrayNode(), List.of()))
                .hasMessageContaining("未公布明确");
        assertThat(MainlandMovieFetcher.date("待定")).isNull();
        assertThat(MainlandMovieFetcher.date("2026-02-30")).isNull();
    }

    @Test void entityEncodedTagsAreNotRestoredAfterUnescape() {
        // 实体编码的标签必须先在文本态被清掉，而不是 unescape 后再还原成真标签入库。
        assertThat(MainlandMovieFetcher.plainText("&lt;script&gt;alert(1)&lt;/script&gt;真实简介"))
                .isEqualTo("真实简介").doesNotContain("<script>");
        assertThat(MainlandMovieFetcher.plainText("<p>第一段</p><p>第二段</p>").replace(" ", ""))
                .isEqualTo("第一段第二段").doesNotContain("<p>");
        assertThat(MainlandMovieFetcher.plainText("  </br> 纯文本  ")).isEqualTo("纯文本");
    }

    @Test void urlOnlyAllowsHttpAndStripsWhImagesAreHttpsUpgrade() {
        assertThat(MainlandMovieFetcher.urlPublic("https://p0.pipi.cn/x/w.h/ab.jpg")).isEqualTo("https://p0.pipi.cn/x/ab.jpg");
        // http 升级为 https
        assertThat(MainlandMovieFetcher.urlPublic("http://p0.pipi.cn/x/ab.jpg")).startsWith("https://");
        // 非 http(s) 一律拒绝
        assertThat(MainlandMovieFetcher.urlPublic("ftp://p0.pipi.cn/x/ab.jpg")).isNull();
        assertThat(MainlandMovieFetcher.urlPublic("not a url")).isNull();
    }

    @Test void notYetRatedIsNotAnArtificialZeroRating() throws Exception {
        var candidate = new MaoyanClient.Candidate(12, "待映影片", "2099-10-01");
        var detail = json.readTree("{\"id\":12,\"nm\":\"待映影片\",\"sc\":0,\"img\":\"https://example.com/a.jpg\"}");
        var result = fetcher.parse(candidate, detail, json.createArrayNode(), List.of());
        assertThat(result.snapshot().sc()).isNull();
        assertThat(result.snapshot().dur()).isNull();
        assertThat(result.snapshot().language()).isNull();
        assertThat(result.snapshot().status()).isEqualTo(2);
    }
}
