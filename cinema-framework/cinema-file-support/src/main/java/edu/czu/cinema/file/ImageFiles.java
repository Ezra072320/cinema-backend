package edu.czu.cinema.file;

import java.util.function.Supplier;

/** 图片业务 Interface。字节存储于现有阿里云 OSS；失败时抛出异常，不返回来源图床地址。 上传仅支持最多 10 MiB 的 JPEG、PNG、WEBP，类型以文件头为准。 */
public interface ImageFiles {
  /** 用户上传：服务端生成日期目录及随机对象名，返回现有格式的 HTTPS URL。 */
  String uploadImage(byte[] bytes);

  /** 影片图片：稳定对象名，已有对象直接复用，仅缺失时调用下载器。 */
  String storeMovieImage(MovieImageSource source, Supplier<byte[]> download);

  /** 是否为当前 Bucket 的 HTTPS 图片地址；不向网络查询，也不修改旧地址。 */
  boolean owns(String url);
}
