package com.bootshift.tests.ai;

import com.bootshift.adapters.ai.LocalOssAIProvider;
import com.bootshift.core.policy.LicensePolicy;
import com.bootshift.ports.ai.AIProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI boundary tests (spec sections 29 and 58).
 *
 * <p>The properties under test are the ones that keep AI optional and non-authorizing: it is off by
 * default, it cannot be pointed at a remote endpoint in strict mode, an unverified model license
 * disables it, and every proposal is a hypothesis until deterministic verification passes.
 */
class AiBoundaryTest {

    @Test
    @DisplayName("AI is disabled by default and returns no proposal")
    void disabledByDefault() {
        AIProvider provider = LocalOssAIProvider.disabled();

        assertThat(provider.enabled()).isFalse();
        assertThat(provider.propose(AIProvider.Task.RESIDUAL_PATCH_PROPOSAL, "prompt",
                Map.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a remote inference endpoint is refused in strict mode")
    void remoteEndpointIsRefused() {
        AIProvider provider = new LocalOssAIProvider(true, "https://api.example.com",
                "hosted", "some-model", "Apache-2.0", new LicensePolicy());

        assertThat(provider.enabled()).isFalse();
    }

    @Test
    @DisplayName("a model whose license fails the gate disables the provider")
    void unverifiedModelLicenseDisablesProvider() {
        AIProvider provider = new LocalOssAIProvider(true, "http://127.0.0.1:11434",
                "ollama", "restricted-model", "Proprietary", new LicensePolicy());

        assertThat(provider.enabled()).isFalse();
        assertThat(provider.identity().licenseVerified()).isFalse();
    }

    @Test
    @DisplayName("an unknown model license is not treated as acceptable")
    void unknownModelLicenseIsNotAcceptable() {
        AIProvider provider = new LocalOssAIProvider(true, "http://127.0.0.1:11434",
                "ollama", "mystery-model", null, new LicensePolicy());

        assertThat(provider.enabled()).isFalse();
        assertThat(provider.identity().licenseVerified()).isFalse();
    }

    @Test
    @DisplayName("the runtime license says nothing about the model license")
    void runtimeLicenseIsNotModelLicense() {
        // The runtime here would pass the gate on its own; the model must pass separately.
        AIProvider provider = new LocalOssAIProvider(true, "http://127.0.0.1:11434",
                "ollama", "weights-under-custom-terms", "Elastic License", new LicensePolicy());

        assertThat(provider.identity().runtime()).isEqualTo("ollama");
        assertThat(provider.identity().licenseVerified()).isFalse();
        assertThat(provider.enabled()).isFalse();
    }

    @Test
    @DisplayName("a permitted local model with an allowlisted license is admitted")
    void permittedLocalModelIsAdmitted() {
        AIProvider provider = new LocalOssAIProvider(true, "http://127.0.0.1:11434",
                "ollama", "qwen2.5-coder", "Apache-2.0", new LicensePolicy());

        assertThat(provider.identity().licenseVerified()).isTrue();
        assertThat(provider.enabled()).isTrue();
        // Enabled is not the same as reachable: with no runtime listening there is still no proposal.
        assertThat(provider.propose(AIProvider.Task.MIGRATION_EXPLANATION, "prompt",
                Map.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("model identity is recorded for evidence even when AI is off")
    void identityIsAlwaysRecordable() {
        AIProvider.ModelIdentity identity = LocalOssAIProvider.disabled().identity();

        assertThat(identity).isNotNull();
        assertThat(identity.runtime()).isEqualTo("none");
        assertThat(identity.licenseVerified()).isFalse();
    }
}
