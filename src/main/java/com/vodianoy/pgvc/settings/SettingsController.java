package com.vodianoy.pgvc.settings;

import com.vodianoy.pgvc.diff.DiffIgnoreRules;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Default ignore rules of schema comparisons. */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final DiffSettingsService settings;

    @GetMapping("/ignore-rules")
    public DiffIgnoreRules getIgnoreRules() {
        return settings.getIgnoreRules();
    }

    @PutMapping("/ignore-rules")
    public DiffIgnoreRules saveIgnoreRules(@RequestBody DiffIgnoreRules rules) {
        return settings.saveIgnoreRules(rules);
    }
}
