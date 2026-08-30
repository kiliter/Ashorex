package com.shangan.admin;

import com.shangan.ai.transcript.TranscriptionJobService;
import com.shangan.catalog.application.CourseSyncService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/** 管理员查看、触发和重试视频转写；页面不展示密钥、完整请求或第三方错误正文。 */
@Controller
public class TranscriptionAdminController {
  private final TranscriptionJobService jobs;
  private final CourseSyncService courses;

  public TranscriptionAdminController(TranscriptionJobService jobs, CourseSyncService courses) {
    this.jobs = jobs;
    this.courses = courses;
  }

  @GetMapping("/admin/transcriptions")
  String transcriptions(Model model) {
    model.addAttribute("jobs", jobs.list());
    model.addAttribute("lessons", courses.listAllAdminLessons());
    return "admin/transcriptions";
  }

  @PostMapping("/admin/transcriptions/{mediaItemId}")
  String start(@PathVariable String mediaItemId) {
    jobs.start(mediaItemId);
    return "redirect:/admin/transcriptions";
  }

  @PostMapping("/admin/transcriptions/{mediaItemId}/retry")
  String retry(@PathVariable String mediaItemId) {
    jobs.start(mediaItemId);
    return "redirect:/admin/transcriptions";
  }
}
