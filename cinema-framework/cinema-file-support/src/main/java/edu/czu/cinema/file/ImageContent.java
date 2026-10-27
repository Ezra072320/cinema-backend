package edu.czu.cinema.file;

/** 用户上传与外部图床共用的图片白名单；不信任文件名和 HTTP Content-Type。 */
public final class ImageContent {
  public static final int MAX_BYTES = 10 * 1024 * 1024;

  public record Type(String extension, String contentType) {}

  private ImageContent() {}

  public static Type requireSupported(byte[] data) {
    if (data == null || data.length == 0) throw new IllegalArgumentException("上传内容不能为空");
    if (data.length > MAX_BYTES) throw new IllegalArgumentException("图片不能超过 10MB");
    if (data.length >= 12) {
      if ((data[0] & 255) == 255 && (data[1] & 255) == 216 && (data[2] & 255) == 255)
        return new Type(".jpg", "image/jpeg");
      if ((data[0] & 255) == 137
          && data[1] == 80
          && data[2] == 78
          && data[3] == 71
          && data[4] == 13
          && data[5] == 10
          && data[6] == 26
          && data[7] == 10) return new Type(".png", "image/png");
      if (data[0] == 'R'
          && data[1] == 'I'
          && data[2] == 'F'
          && data[3] == 'F'
          && data[8] == 'W'
          && data[9] == 'E'
          && data[10] == 'B'
          && data[11] == 'P') return new Type(".webp", "image/webp");
    }
    throw new IllegalArgumentException("仅支持 JPG/PNG/WEBP 图片");
  }
}
