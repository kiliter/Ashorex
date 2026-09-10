package com.shangan.appupdate;

import com.shangan.common.api.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 仅分发公开的正式安装包；升级状态与 Docker updater 完全独立。 */
@Service
public class AppUpdateService {
  private final GithubReleaseClient github;
  private final Semaphore downloads = new Semaphore(4);

  public AppUpdateService(GithubReleaseClient github) {
    this.github = github;
  }

  public AppRelease latest(String platform) {
    AppRelease.platform(platform);
    return AppRelease.parse(github.latest(), platform);
  }

  public AppRelease release(String tag, String platform) {
    return AppRelease.parse(github.release(AppRelease.tag(tag)), AppRelease.platform(platform));
  }

  /** 同步流式中转让客户端断开及时关闭上游；只转发下载协议所需的安全头。 */
  public void download(
      String tag, String platform, String range, String ifRange, HttpServletResponse response)
      throws IOException {
    AppRelease.platform(platform);
    AppRelease.tag(tag);
    if (range != null && !range.matches("bytes=([0-9]+-[0-9]*|-[0-9]+)"))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "APP_UPDATE_RANGE_INVALID", "只支持单段字节范围下载");
    if (!downloads.tryAcquire())
      throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "APP_UPDATE_BUSY", "下载人数较多，请稍后重试");
    HttpURLConnection connection = null;
    try {
      AppRelease release = release(tag, platform);
      if (!release.downloadable())
        throw new BusinessException(
            HttpStatus.NOT_FOUND, "APP_UPDATE_ASSET_MISSING", "该平台安装包尚未发布完整");
      connection = github.open(URI.create(AppRelease.assetUrl(tag, platform)), range, ifRange);
      int code = connection.getResponseCode();
      if (code != 200 && code != 206 && code != 416) throw GithubReleaseClient.unavailable();
      response.setStatus(code);
      for (String header :
          List.of("Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
        String value = connection.getHeaderField(header);
        if (value != null) response.setHeader(header, value);
      }
      response.setHeader("Cache-Control", "private, no-cache");
      if (code == 416) {
        response.setContentLength(0);
        return;
      }
      response.setContentType(
          "android".equals(platform)
              ? "application/vnd.android.package-archive"
              : "application/octet-stream");
      response.setHeader(
          "Content-Disposition",
          "attachment; filename=\"" + AppRelease.filename(release.version(), platform) + "\"");
      try (var input = connection.getInputStream()) {
        input.transferTo(response.getOutputStream());
      }
    } catch (IOException e) {
      // 响应尚未开始时返回安全错误；传输中断不拼接 JSON 污染安装包。
      if (!response.isCommitted()) {
        response.reset();
        throw GithubReleaseClient.unavailable();
      }
    } finally {
      if (connection != null) connection.disconnect();
      downloads.release();
    }
  }

  /** 外部浏览器直接使用匿名同源链接，不需要 App token，不渲染上游 HTML。 */
  public String iosPage(String tag) {
    AppRelease release = release(tag, "ios");
    String action =
        release.downloadable()
            ? "<a href=\""
                + release.downloadPath()
                + "\">下载 IPA（"
                + (release.size() / 1024 / 1024)
                + " MB）</a><p>SHA-256：<code>"
                + release.sha256()
                + "</code></p>"
            : "<p>iOS 安装包尚未发布完整，请稍后重试。</p>";
    return """
      <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>上岸 iOS 更新</title><style>body{font:17px system-ui;line-height:1.7;max-width:640px;margin:40px auto;padding:20px}a{display:inline-block;padding:12px;background:#286257;color:white;border-radius:8px}code{overflow-wrap:anywhere}h1{font-size:26px}</style>
      <h1>上岸 %s</h1><p>请先下载安装包，再使用轻松签自行签名安装。此页面不会自动签名或安装。</p>
      %s<p>覆盖安装是否保留数据取决于签名身份与系统安装规则，请沿用原签名方式。请勿删除旧 App 后再安装。</p></html>
      """
        .formatted(release.version(), action);
  }
}
