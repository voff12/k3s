package com.example.k3sdemo.delivery.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Application.runtimeType → 流水线 runtime 归一化映射。 */
class BuildKitPipelineTriggerTest {

    @Test
    void mapsKnownRuntimesAsIs() {
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("java")).isEqualTo("java");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("python")).isEqualTo("python");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("static")).isEqualTo("static");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("dockerfile")).isEqualTo("dockerfile");
    }

    @Test
    void mapsDockerToFrontendAliases() {
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("docker")).isEqualTo("dockerfile");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("node")).isEqualTo("static");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("frontend")).isEqualTo("static");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("web")).isEqualTo("static");
    }

    @Test
    void nullOrBlankOrUnknownFallsBackToAuto() {
        assertThat(BuildKitPipelineTrigger.normalizeRuntime(null)).isEqualTo("auto");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("  ")).isEqualTo("auto");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("golang")).isEqualTo("auto");
    }

    @Test
    void trimsAndLowercases() {
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("  Frontend ")).isEqualTo("static");
        assertThat(BuildKitPipelineTrigger.normalizeRuntime("Python")).isEqualTo("python");
    }
}
