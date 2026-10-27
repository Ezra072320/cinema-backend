package edu.czu.cinema.remote;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(name="cinema.operations.enabled",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
public class OperationController {
    private final OperationJournal journal;

    @GetMapping("/internal/operations/{key}")
    public String result(@PathVariable String key, @RequestParam Long userId) {
        return journal.result(key, userId);
    }
}
