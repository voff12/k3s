package com.example.k3sdemo.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** static 运行时输入校验：合法配置放行，注入形态（换行/路径穿越/非法版本号）拒绝。 */
class RuntimeValidationTest {

    private String validate(PipelineConfig config) {
        return RuntimeValidation.validate(config.getRuntime(), config.getPythonVersion(),
                config.getAppPort(), config.getPythonServer(), config.getAppModule(),
                config.getRequirementsPath(), config.getNodeVersion(),
                config.getNpmBuildCommand(), config.getStaticDistDir());
    }

    @Test
    void staticRuntimeWithDefaultsPasses() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        assertThat(validate(config)).isNull();
        assertThat(config.validateRuntimeFields()).isNull();
    }

    @Test
    void staticDefaultsToPort80() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        assertThat(config.getEffectiveAppPort()).isEqualTo(80);
        assertThat(config.isStatic()).isTrue();
    }

    @Test
    void appPortOverridesStaticDefault() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        config.setAppPort(8080);
        assertThat(config.getEffectiveAppPort()).isEqualTo(8080);
    }

    @Test
    void invalidNodeVersionRejected() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        config.setNodeVersion("20; rm -rf /");
        assertThat(validate(config)).contains("nodeVersion");
    }

    @Test
    void newlineInNpmBuildCommandRejected() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        config.setNpmBuildCommand("npm run build\nRUN echo hacked");
        assertThat(validate(config)).contains("npmBuildCommand");
    }

    @Test
    void pathTraversalInDistDirRejected() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        config.setStaticDistDir("../etc");
        assertThat(validate(config)).contains("staticDistDir");
    }

    @Test
    void absoluteDistDirRejected() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        config.setStaticDistDir("/usr/share/nginx/html");
        assertThat(validate(config)).contains("staticDistDir");
    }

    @Test
    void unknownRuntimeStillRejected() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("golang");
        assertThat(validate(config)).contains("非法 runtime");
    }

    @Test
    void releaseConfigLegacyFormUnaffected() {
        // 老发布表单无 static 字段：python/java 语义不变
        ReleaseConfig config = new ReleaseConfig();
        config.setRuntime("python");
        config.setPythonVersion("3.12");
        assertThat(config.validateRuntimeFields()).isNull();
    }
}
