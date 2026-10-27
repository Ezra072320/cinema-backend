package edu.czu.cinema.notify;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notify")
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/messages")
  public R<PageResult<NotificationService.Item>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "false") boolean unread) {
    return R.ok(notifications.list(SecurityUtils.requireUserId(), page, size, unread));
  }

  @GetMapping("/unread-count")
  public R<Long> unread() {
    return R.ok(notifications.unread(SecurityUtils.requireUserId()));
  }

  @PutMapping("/messages/{id}/read")
  public R<Void> markRead(@PathVariable Long id) {
    notifications.markRead(SecurityUtils.requireUserId(), id);
    return R.ok();
  }

  @PutMapping("/messages/read-all")
  public R<Void> markAllRead() {
    notifications.markAllRead(SecurityUtils.requireUserId());
    return R.ok();
  }
}
