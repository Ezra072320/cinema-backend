package edu.czu.cinema.remote;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class OperationController {
    private final OperationJournal journal;

    @GetMapping("/internal/operations/{key}")
    public String result(@PathVariable String key, @RequestParam Long userId) {
        return journal.result(key, userId);
    }
}
