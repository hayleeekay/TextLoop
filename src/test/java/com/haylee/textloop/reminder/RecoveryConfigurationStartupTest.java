package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.haylee.textloop.TextloopApplication;

class RecoveryConfigurationStartupTest {
    private static final String DIAGNOSTIC =
            "Recovery startup or execution failed. Inspect delivery state before retrying.";
    private static final String MARKER = "private-startup-marker.+12035550100";

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "properties"})
    void configurationLoadingFailureInRecoveryExitsSafely(String extension) throws Exception {
        String location = directory.resolve(MARKER + "." + extension).toUri().toString();
        var result = runApplication("--textloop.recovery=true", "--spring.config.additional-location=" + location);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains(DIAGNOSTIC)
                .doesNotContain(location, MARKER, "+12035550100", "IllegalStateException", "Caused by:",
                        "APPLICATION FAILED TO START", "[FAKE SMS]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "properties"})
    void normalConfigurationLoadingFailureKeepsItsOriginalDetails(String extension) throws Exception {
        String location = directory.resolve(MARKER + "." + extension).toUri().toString();
        var result = runApplication("--spring.config.additional-location=" + location);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains(MARKER).doesNotContain(DIAGNOSTIC);
    }

    @Test
    void recoveryModeFromLoadedConfigurationStillProtectsLaterBindingFailure() throws Exception {
        Path configuration = directory.resolve("recovery.properties");
        Files.writeString(configuration, "textloop.recovery=true\nspring.main.banner-mode=" + MARKER + "\n");
        var result = runApplication("--spring.config.additional-location=" + configuration.toUri());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains(DIAGNOSTIC)
                .doesNotContain(MARKER, "+12035550100", "Caused by:", "[FAKE SMS]");
    }

    private ProcessResult runApplication(String... options) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), TextloopApplication.class.getName()));
        command.addAll(List.of(options));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            return new ProcessResult(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private record ProcessResult(int exitCode, String output) {}
}
