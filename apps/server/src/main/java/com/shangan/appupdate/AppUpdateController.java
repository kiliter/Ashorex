package com.shangan.appupdate;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;

/** 移动端主动检查及公开安装包下载；路径不接收任意源站地址。 */
@RestController
public class AppUpdateController {
  private final AppUpdateService updates;

  public AppUpdateController(AppUpdateService updates) {
    this.updates = updates;
  }

  @GetMapping("/api/v1/app-updates/latest")
  public AppRelease latest(@RequestParam String platform) {
    return updates.latest(platform);
  }

  @GetMapping("/api/v1/app-updates/releases/{tag}/assets/{platform}")
  public void download(
      @PathVariable String tag,
      @PathVariable String platform,
      @RequestHeader(value = "Range", required = false) String range,
      @RequestHeader(value = "If-Range", required = false) String ifRange,
      HttpServletResponse response)
      throws IOException {
    updates.download(tag, platform, range, ifRange, response);
  }

  @GetMapping(value = "/downloads/app/{tag}/ios", produces = "text/html;charset=UTF-8")
  public String iosPage(@PathVariable String tag) {
    return updates.iosPage(tag);
  }
}
