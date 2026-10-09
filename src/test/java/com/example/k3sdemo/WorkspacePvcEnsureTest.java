package com.example.k3sdemo;

import com.example.k3sdemo.service.DevOpsService;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ensureWorkspacePvc 修复回归：fabric8 get() 在资源不存在时返回 null（不抛 404），
 * 旧实现用 try/catch(404) 判断"需创建"，导致 null 被当成"已存在"直接 return，
 * PVC 永不创建、Pod 卡 FailedScheduling。修复后必须用 null 判断。
 */
class WorkspacePvcEnsureTest {

    private static final String SRC = "src/main/java/com/example/k3sdemo/service/DevOpsService.java";

    @Test
    void ensureWorkspacePvc_usesNullCheckNot404Catch() throws IOException {
        String src = Files.readString(Path.of(SRC));
        // 修复标志：用 get() != null 判断已存在
        assertTrue(src.contains(".withName(pvcName).get() != null"),
                "ensureWorkspacePvc 必须用 get()!=null 判断 PVC 已存在（fabric8 不存在时返回 null 而非抛 404）");
        // 旧的错误模式（catch 404 决定创建）不应再存在于 ensureWorkspacePvc 方法体内
        int start = src.indexOf("ensureWorkspacePvc(KubernetesClient");
        int end = src.indexOf("buildWorkspacePvc(String", start);
        String body = src.substring(start, end);
        assertFalse(body.contains("catch (KubernetesClientException notFound)"),
                "不应再用 catch(404) 判断 PVC 不存在");
        assertTrue(body.contains("buildWorkspacePvc(pvcName)"),
                "创建路径应调用 buildWorkspacePvc");
    }

    @Test
    void buildWorkspacePvc_specIsCorrect() {
        PersistentVolumeClaim pvc = DevOpsService.buildWorkspacePvc("workspace-pvc-abc12345");
        assertEquals("workspace-pvc-abc12345", pvc.getMetadata().getName());
        assertEquals("default", pvc.getMetadata().getNamespace());
        assertEquals(java.util.List.of("ReadWriteOnce"), pvc.getSpec().getAccessModes());
        assertEquals("5Gi", pvc.getSpec().getResources().getRequests().get("storage").toString());
    }
}
