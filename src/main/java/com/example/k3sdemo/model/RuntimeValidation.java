package com.example.k3sdemo.model;

/**
 * 运行时(Python/Java)相关字段的输入校验,防止拼入生成 Dockerfile / shell 时被注入。
 * 见 PYTHON-RELEASE-DESIGN.md 第 4.1 节。
 */
final class RuntimeValidation {

    private RuntimeValidation() {
    }

    /**
     * 校验运行时字段。合法返回 null,否则返回错误信息。
     * 仅在 runtime=python 时严格校验 Python 专属字段。
     */
    static String validate(String runtime, String pythonVersion, int appPort,
            String pythonServer, String appModule, String requirementsPath) {
        String r = runtime == null ? "" : runtime.trim().toLowerCase();
        boolean isPython = "python".equals(r);

        // runtime 取值
        if (!r.isEmpty() && !r.equals("auto")
                && !r.equals("java") && !r.equals("python") && !r.equals("dockerfile")) {
            return "非法 runtime: " + runtime;
        }

        // 端口:0 表示按默认;否则 1-65535
        if (appPort != 0 && (appPort < 1 || appPort > 65535)) {
            return "appPort 必须在 1-65535 之间";
        }

        if (isPython) {
            if (pythonVersion != null && !pythonVersion.isEmpty()
                    && !pythonVersion.matches("[0-9.]+")) {
                return "非法 pythonVersion: " + pythonVersion;
            }
            if (pythonServer != null && !pythonServer.isEmpty()
                    && !pythonServer.equals("gunicorn") && !pythonServer.equals("uvicorn")) {
                return "pythonServer 仅支持 gunicorn / uvicorn";
            }
            if (appModule != null && !appModule.isEmpty()
                    && !appModule.matches("[A-Za-z0-9_.:]+")) {
                return "非法 appModule(仅允许字母数字及 . _ :): " + appModule;
            }
            if (requirementsPath != null && !requirementsPath.isEmpty()
                    && (requirementsPath.startsWith("/") || requirementsPath.contains(".."))) {
                return "非法 requirementsPath(禁止绝对路径与 ..): " + requirementsPath;
            }
        }
        return null;
    }
}
