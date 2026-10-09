package com.example.k3sdemo.delivery.service;

import java.util.Map;

/**
 * 发布执行触发器抽象：交付中心通过它启动一次真实构建部署并查询进度。
 * 生产实现为 BuildKitPipelineTrigger（包装现有 DevOpsService 流水线）；
 * 测试用假实现验证阶段推进。
 */
public interface PipelineTrigger {

    /**
     * 触发一次构建部署。
     *
     * @param gitUrl      代码仓库
     * @param branch      分支
     * @param imageName   目标镜像名（不含 tag）
     * @param deployment  目标 Deployment 名（可空 = 仅构建）
     * @param namespace   目标命名空间
     * @return 流水线 ID（用于后续 poll）
     */
    String trigger(String gitUrl, String branch, String imageName,
                   String deployment, String namespace);

    /**
     * 查询流水线当前状态。
     *
     * @return 至少包含：status（PENDING/CLONING/MERGING/BUILDING/PUSHING/DEPLOYING/SUCCESS/FAILED）、
     *         finished（布尔）、imageRef（成功时镜像完整引用）、error（失败原因）
     */
    Map<String, Object> poll(String pipelineId);

    /** 触发失败（配置/网络层面）时抛出 */
    class TriggerException extends RuntimeException {
        public TriggerException(String message) {
            super(message);
        }
        public TriggerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
