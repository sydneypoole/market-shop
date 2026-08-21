package com.marketshop.bootstrap.identity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessE2eBootstrapInvitationContractTest {

    @Test
    void registersOnlyTheFirstNewMemberWithTheSingleUseBootstrapInvitation() throws IOException {
        String script = Files.readString(repositoryFile("scripts/business-e2e.sh"))
                .replaceAll("\\s+", " ")
                .trim();

        assertThat(script)
                .contains("login_user \"${child_jar}\" \"e2e-child-${run_key}\" 'E2E 买家' \"${invite_code}\"")
                .contains("api_json POST '/api/v1/membership/invitation' \"${sponsor_jar}\"")
                .contains("sponsor_invitation_code=\"$(jq -er '.data.code' \"${body_file}\")\"")
                .contains("login_user \"${outsider_jar}\" \"e2e-outsider-${run_key}\" 'E2E 旁观者' \"${sponsor_invitation_code}\"")
                .doesNotContain("login_user \"${outsider_jar}\" \"e2e-outsider-${run_key}\" 'E2E 旁观者' \"${invite_code}\"");
    }

    private static Path repositoryFile(String relativePath) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("repository file not found: " + relativePath);
    }
}
