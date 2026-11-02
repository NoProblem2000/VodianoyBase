package com.vodianoy.pgvc.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI mode: active only when the jar is started with {@code --cli}. Then the web server is not started (see
 * {@link com.vodianoy.pgvc.PgGitVcApplication#main}), the command runs and its result becomes the process exit code.
 */
@Component
@RequiredArgsConstructor
public class CliCommandRunner implements ApplicationRunner, ExitCodeGenerator {

    public static final String FLAG = "cli";

    private final CliCommands commands;
    private int exitCode = CliCommands.OK;

    /** True when the command line asks for the CLI mode. */
    public static boolean isCli(String[] args) {
        for (String a : args) {
            if (a.equals("--" + FLAG) || a.startsWith("--" + FLAG + "=")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(FLAG)) {
            return;
        }
        exitCode = commands.run(options(args), System.out, System.err);
        System.out.flush();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** {@code --name=value} options (the last value wins); {@code --name} alone gives an empty value. */
    static Map<String, String> options(ApplicationArguments args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : args.getOptionNames()) {
            List<String> values = args.getOptionValues(name);
            result.put(name, values == null || values.isEmpty() ? "" : values.get(values.size() - 1));
        }
        return result;
    }
}
