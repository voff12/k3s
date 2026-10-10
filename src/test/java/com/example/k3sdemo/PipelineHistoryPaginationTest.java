package com.example.k3sdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线历史分页回归：
 * - 控制器必须提供分页接口 /devops/pipelines/page（page/size 参数）；
 * - 首屏渲染只取第一页（不允许把全量记录塞进模板模型）；
 * - 前端 refreshPipelines 必须走分页接口并渲染分页条。
 */
class PipelineHistoryPaginationTest {

    private static final String CONTROLLER = "src/main/java/com/example/k3sdemo/controller/DevOpsController.java";
    private static final String TEMPLATE = "src/main/resources/templates/devops.html";

    @Test
    void controllerProvidesPagedEndpoint() throws IOException {
        String src = Files.readString(Path.of(CONTROLLER));
        assertTrue(src.contains("\"/devops/pipelines/page\""),
                "控制器缺少分页接口 /devops/pipelines/page");
        assertTrue(src.contains("@RequestParam(defaultValue = \"1\") int page"),
                "分页接口缺少 page 参数（默认 1，从 1 开始计数）");
        assertTrue(src.contains("@RequestParam(defaultValue = \"10\") int size"),
                "分页接口缺少 size 参数（默认 10）");
        assertTrue(src.contains("firstPageOf(runs)"),
                "首屏渲染应只注入第一页数据，而非全量列表");
    }

    @Test
    void frontendFetchesByPageAndRendersPager() throws IOException {
        String src = Files.readString(Path.of(TEMPLATE));
        assertTrue(src.contains("/devops/pipelines/page?page="),
                "前端应通过分页接口拉取历史");
        assertTrue(src.contains("renderPagination"),
                "前端应渲染分页条");
        assertTrue(src.contains("goToPage"),
                "前端应支持点击页码翻页");
    }

    /**
     * 回归：分页条 #historyPagination 必须在 #pipelineHistory 容器之外。
     * 曾因插在容器内部，refreshPipelines() 的 innerHTML 覆盖把分页条整块删掉
     * （真实浏览器中分页条永远不显示）。通过 div 配平定位容器结束位置来校验。
     */
    @Test
    void pagerMustLiveOutsideHistoryContainer() throws IOException {
        String src = Files.readString(Path.of(TEMPLATE));
        int containerStart = src.indexOf("id=\"pipelineHistory\"");
        assertTrue(containerStart > 0, "模板缺少 #pipelineHistory 容器");

        int depth = 0;
        int containerEnd = -1;
        int i = containerStart;
        while (i < src.length()) {
            int open = src.indexOf("<div", i);
            int close = src.indexOf("</div>", i);
            if (close < 0) {
                break;
            }
            if (open >= 0 && open < close) {
                depth++;
                i = open + 4;
            } else {
                depth--;
                i = close + 6;
                if (depth == 0) {
                    containerEnd = i;
                    break;
                }
            }
        }
        assertTrue(containerEnd > 0, "无法定位 #pipelineHistory 容器结束标签");

        int pager = src.indexOf("id=\"historyPagination\"");
        assertTrue(pager > 0, "模板缺少分页条 #historyPagination");
        assertTrue(pager > containerEnd,
                "分页条必须位于 #pipelineHistory 容器之外，否则会被 refreshPipelines 的 innerHTML 覆盖删除");
    }
}
