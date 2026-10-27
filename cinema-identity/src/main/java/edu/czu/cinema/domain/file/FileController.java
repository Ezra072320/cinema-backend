package edu.czu.cinema.domain.file;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.file.ImageContent;
import edu.czu.cinema.file.ImageFiles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "12. 文件上传")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

  /** 单用户每日上传上限，防「免费网盘」式滥用与存储成本失控. */
  private static final int DAILY_UPLOAD_LIMIT = 50;

  private final ImageFiles images;
  private final StringRedisTemplate redis;

  @Operation(summary = "图片上传（仅 JPG/PNG/WEBP，服务端魔数校验），返回公网 URL")
  @PostMapping("/upload")
  public R<String> upload(@RequestParam("file") MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
    if (file.getSize() > ImageContent.MAX_BYTES) throw new IllegalArgumentException("图片不能超过 10MB");
    Long userId = ContextHolder.currentId();
    if (userId != null) {
      String key = "upload:quota:" + userId + ":" + LocalDate.now();
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redis.expire(key, Duration.ofDays(1));
      }
      if (count != null && count > DAILY_UPLOAD_LIMIT) {
        throw new BusinessException("今日上传次数已达上限（" + DAILY_UPLOAD_LIMIT + " 次），请明天再试");
      }
    }
    return R.ok("上传成功", images.uploadImage(file.getBytes()));
  }
}
