package edu.czu.cinema.common.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import edu.czu.cinema.common.exception.BusinessException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import edu.czu.cinema.configproperties.AliOssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 阿里云 OSS 上传工具（替换原项目的 {@code AliOSSUtil}）.
 * <p>
 * 上传后返回公网可访问 URL；目录策略：{@code cinema/yyyy/MM/dd/uuid-原始名}.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliOssUtil {

    /** 允许上传的图片类型（按文件头识别，客户端扩展名/Content-Type 一律不信任）. */
    public record ImageType(String ext, String contentType) {
    }

    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private final AliOssProperties properties;

    /**
     * 通用上传：只接受 JPG/PNG/WEBP 图片。
     * <p>
     * 扩展名与 Content-Type 均由服务端按魔数判定——此前直接信任
     * {@code originalFilename} 与请求头，任意登录用户可上传 HTML/SVG
     * 到公共读 bucket，得到可托管钓鱼页的同源 URL。
     * </p>
     */
    public String upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("图片不能超过 10MB");
        }
        byte[] data = file.getBytes();
        ImageType type = detectImageType(data);
        if (type == null) {
            throw new IllegalArgumentException("仅支持 JPG/PNG/WEBP 图片");
        }
        String key = "cinema/"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                + "/" + UUID.randomUUID().toString().replace("-", "") + type.ext();

        OSS oss = client();
        try {
            ObjectMetadata meta = new ObjectMetadata();
            // 服务端判定类型，不信任客户端 Content-Type
            meta.setContentType(type.contentType());
            meta.setContentLength(data.length);
            // 与 uploadBytes 统一显式 ACL，避免 bucket 私有/公有差异导致 403 或意外公开
            meta.setHeader("x-oss-object-acl", "public-read");
            oss.putObject(new PutObjectRequest(properties.getBucketName(), key, new ByteArrayInputStream(data), meta));
        } finally {
            oss.shutdown();
        }
        return publicUrl(key);
    }

    /** 按魔数识别图片类型；非白名单返回 null. */
    public static ImageType detectImageType(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return new ImageType(".jpg", "image/jpeg");
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return new ImageType(".png", "image/png");
        }
        // WEBP: "RIFF" ???? "WEBP"
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return new ImageType(".webp", "image/webp");
        }
        return null;
    }

    /**
     * 按固定 key 上传一段内存字节（影片海报/剧照抓取转存用）.
     *
     * <p>与 {@link #upload(MultipartFile)} 的两点差异，都是踩过的坑：
     * <ol>
     *   <li><b>逐对象显式设置 {@code x-oss-object-acl: public-read}</b>。
     *       不能指望 bucket 自身的 ACL——如果 bucket 是私有的，前端 {@code <img>}
     *       拿到的就是 403，海报照样显示不出来。这里跟 bucket 策略解耦。</li>
     *   <li>key 由调用方给定（{@code cinema/movies/posters/{sid}.jpg}），
     *       重复同步是幂等覆盖，不会像 uuid 命名那样越传越多垃圾对象。</li>
     * </ol>
     *
     * @param data        图片字节
     * @param key         OSS 对象 key
     * @param contentType 如 {@code image/jpeg}
     * @return 公网可访问 URL
     */
    public String uploadBytes(byte[] data, String key, String contentType) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("上传内容不能为空");
        }
        OSS oss = client();
        try {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentType(contentType);
            meta.setContentLength(data.length);
            meta.setHeader("x-oss-object-acl", "public-read");
            oss.putObject(new PutObjectRequest(properties.getBucketName(), key, new ByteArrayInputStream(data), meta));
        } finally {
            oss.shutdown();
        }
        return publicUrl(key);
    }

    /** 由 key 拼公网 URL（兼容 endpoint 带/不带协议头）. */
    public String publicUrl(String key) {
        String host = properties.getEndpoint()
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");
        return "https://" + properties.getBucketName() + "." + host + "/" + key;
    }

    public boolean objectExists(String key) {
        OSS oss = client();
        try { return oss.doesObjectExist(properties.getBucketName(), key); }
        finally { oss.shutdown(); }
    }

    private OSS client() {
        if (properties.getAccessKeyId() == null || properties.getAccessKeyId().isBlank()
                || properties.getAccessKeySecret() == null || properties.getAccessKeySecret().isBlank()
                || properties.getBucketName() == null || properties.getBucketName().isBlank()) {
            throw new BusinessException("图片存储尚未配置，请联系管理员配置 OSS");
        }
        var config = new ClientBuilderConfiguration();
        config.setConnectionTimeout(3000);
        config.setSocketTimeout(15000);
        config.setRequestTimeout(20000);
        config.setRequestTimeoutEnabled(true);
        config.setMaxErrorRetry(0);
        return new OSSClientBuilder().build(properties.getEndpoint(), properties.getAccessKeyId(),
                properties.getAccessKeySecret(), config);
    }
}
