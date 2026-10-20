package edu.czu.cinema.domain.movie.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.movie.seed.MaoyanImportServiceImpl;
import edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot;
import edu.czu.cinema.domain.movie.sync.MovieMediaStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MovieMetadataService {
    private final MovieMapper movies;
    private final MaoyanImportServiceImpl importer;
    private final MovieMediaStore media;
    private final ObjectMapper json;

    public record Edit(Map<String,String> fields, List<String> unlockFields, Integer expectedRevision) {}

    @Transactional(rollbackFor = Exception.class)
    public Movie edit(Long movieId, Edit request) {
        if (request == null || request.expectedRevision() == null) throw bad("缺少编辑版本，请重新打开补录窗口");
        Map<String,String> changes = request.fields() == null ? Map.of() : request.fields();
        List<String> unlock = request.unlockFields() == null ? List.of() : request.unlockFields();
        if (changes.isEmpty() && unlock.isEmpty()) throw bad("请填写需补录的资料或调整人工保护");
        if (!MovieMetadataPolicy.FIELDS.containsAll(changes.keySet()) || !MovieMetadataPolicy.FIELDS.containsAll(unlock))
            throw bad("包含不支持的影片字段");
        if (unlock.stream().anyMatch(changes::containsKey)) throw bad("同一字段不能同时补录和解除保护");
        // 先验证全部输入，再执行任何写入。
        Map<String,Object> fields = new HashMap<>();
        for (var entry : changes.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.isEmpty()) throw bad("补录值不能为空；未知资料请继续保留待补");
            String key = entry.getKey();
            int max = switch (key) { case "summary" -> 10000; case "posterUrl" -> 512; case "area" -> 64; case "language" -> 32; default -> 255; };
            if (value.length() > max) throw bad("字段内容过长：" + key);
            switch (key) {
                case "runtimeMin" -> {
                    try {
                        int runtime = Integer.parseInt(value);
                        if (runtime < 1 || runtime > 1440) throw new NumberFormatException();
                        fields.put("dur",runtime);
                    } catch (NumberFormatException ex) { throw bad("时长需为 1～1440 的整数分钟"); }
                }
                case "releaseDate" -> {
                    try { fields.put("rt",LocalDate.parse(value).toString()); }
                    catch (RuntimeException ex) { throw bad("上映日格式应为 yyyy-MM-dd"); }
                }
                case "posterUrl" -> {
                    if (!media.isStored(value)) throw bad("海报需先上传到本项目的阿里云 OSS");
                    fields.put("img",value);
                }
                default -> fields.put(switch (key) { case "summary" -> "story"; case "area" -> "src"; default -> key; },value);
            }
        }
        Movie movie = movies.lockById(movieId);
        if (movie == null) throw new BusinessException(ResultCode.NOT_FOUND,"电影不存在");
        int revision = movie.getManualRevision() == null ? 0 : movie.getManualRevision();
        if (revision != request.expectedRevision()) throw new BusinessException(ResultCode.CONFLICT,"其他管理员已修改资料，请重新打开补录窗口后再保存");
        Set<String> locked = new LinkedHashSet<>(movie.getLockedFields());
        locked.removeAll(unlock);
        locked.addAll(changes.keySet());
        movie.setManualFieldKeys(String.join(",",MovieMetadataPolicy.FIELDS.stream().filter(locked::contains).toList()));
        movie.setManualRevision(revision + 1);
        // 人工核实：字段刚由管理员确认，必须绕过 lockedFields 过滤，否则新值会被自己刚加的锁挡掉
        importer.saveBasicMetadata(movie,json.convertValue(fields,MaoyanMovieSnapshot.class), true);
        return movie;
    }

    private static BusinessException bad(String message) { return new BusinessException(ResultCode.BAD_REQUEST,message); }
}
