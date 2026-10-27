package edu.czu.cinema.internal;

import edu.czu.cinema.domain.user.dto.UserUpdateDTO;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.mapper.UserMapper;
import edu.czu.cinema.domain.user.service.UserService;
import edu.czu.cinema.remote.OperationJournal;
import edu.czu.cinema.security.AccountStateGuard;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final UserService users;
    private final UserMapper mapper;
    private final AccountStateGuard guard;
    private final OperationJournal journal;

    @GetMapping("/access")
    public edu.czu.cinema.domain.admin.AccountAccess access(
            @RequestParam String role, @RequestParam Long id, @RequestParam String pv) {
        return guard.verify(role, id, pv);
    }

    @GetMapping("/active")
    public boolean active(
            @RequestParam String role, @RequestParam Long id, @RequestParam String pv) {
        return guard.isActive(role, id, pv);
    }

    @GetMapping("/count")
    public long count() {
        return mapper.selectCount(null);
    }

    @GetMapping("/{id}")
    public User user(@PathVariable Long id) {
        return users.getById(id);
    }

    @PutMapping("/{id}")
    public void update(
            @PathVariable Long id,
            @RequestBody UserUpdateDTO dto,
            @RequestHeader("X-Operation-Id") String key) {
        journal.run(
                key,
                id,
                "UPDATE_PROFILE",
                dto,
                Boolean.class,
                () -> {
                    users.updateCurrent(id, dto);
                    return true;
                });
    }
}
