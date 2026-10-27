package edu.czu.cinema.file;

/** 由可信影片抓取器构造；来源 URL 不用于向任意主机发起下载。 */
public record MovieImageSource(String provider, Integer movieId, Kind kind, String sourceUrl) {
  public enum Kind {
    POSTER("posters"),
    BACKDROP("backdrops"),
    STILL("stills");

    private final String directory;

    Kind(String directory) {
      this.directory = directory;
    }

    public String directory() {
      return directory;
    }
  }

  public MovieImageSource {
    if (!"maoyan".equals(provider) && !"tmdb".equals(provider))
      throw new IllegalArgumentException("未知图片来源");
    if (movieId == null || movieId <= 0 || kind == null)
      throw new IllegalArgumentException("缺少影片图片标识");
    if (sourceUrl == null || sourceUrl.isBlank()) throw new IllegalArgumentException("影片图片地址不能为空");
  }
}
