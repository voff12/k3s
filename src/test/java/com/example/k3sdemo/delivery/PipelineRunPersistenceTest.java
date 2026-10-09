package com.example.k3sdemo.delivery;

import com.example.k3sdemo.delivery.entity.PipelineRunEntity;
import com.example.k3sdemo.delivery.repository.PipelineRunRepository;
import com.example.k3sdemo.delivery.service.PipelineRunPersistenceService;
import com.example.k3sdemo.model.PipelineConfig;
import com.example.k3sdemo.model.PipelineRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流水线持久化（方案 B）主链路测试：
 * 保存 → 更新 → 加载恢复 → 僵尸任务标记失败。
 * 跑在 H2(MySQL 兼容模式) 上，验证 Flyway V3 迁移与实体映射一致。
 */
@SpringBootTest
@Transactional
class PipelineRunPersistenceTest {

    @Autowired
    private PipelineRunPersistenceService persistenceService;

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    private PipelineRun newRun() {
        PipelineConfig config = new PipelineConfig();
        config.setGitUrl("https://git.example.com/demo/app.git");
        config.setBranch("main");
        config.setImageName("demo/app");
        config.setImageTag("v1.0.0");
        return new PipelineRun(config);
    }

    @Test
    void onChanged_savesNewRunToDatabase() {
        PipelineRun run = newRun();
        run.addLog("[INFO] 流水线已创建");

        persistenceService.onChanged(run);

        Optional<PipelineRunEntity> found = pipelineRunRepository.findByRunId(run.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("PENDING");
        assertThat(found.get().getLogs()).contains("流水线已创建");
        assertThat(found.get().getConfigJson()).contains("demo/app");
    }

    @Test
    void onCompleted_persistsFinalStatusAndError() {
        PipelineRun run = newRun();
        persistenceService.onChanged(run);

        run.advanceTo(PipelineRun.Status.BUILDING);
        run.fail("BuildKit 构建失败");
        persistenceService.onCompleted(run);

        PipelineRunEntity entity = pipelineRunRepository.findByRunId(run.getId()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getCurrentStep()).isEqualTo(5);
        assertThat(entity.getErrorMessage()).isEqualTo("BuildKit 构建失败");
        assertThat(entity.getFinishedAt()).isNotNull();
    }

    @Test
    void loadAll_restoresFinishedRunsWithLogs() {
        PipelineRun run = newRun();
        run.addLog("[INFO] 步骤1/5: 代码克隆...");
        run.advanceTo(PipelineRun.Status.SUCCESS);
        persistenceService.onCompleted(run);

        List<PipelineRun> restored = persistenceService.loadAll();

        assertThat(restored).hasSize(1);
        PipelineRun r = restored.get(0);
        assertThat(r.getId()).isEqualTo(run.getId());
        assertThat(r.getStatus()).isEqualTo(PipelineRun.Status.SUCCESS);
        assertThat(r.getLogs()).anyMatch(l -> l.contains("代码克隆"));
        assertThat(r.getConfig().getImageName()).isEqualTo("demo/app");
        assertThat(r.isFinished()).isTrue();
    }

    @Test
    void markZombiesFailed_marksNonTerminalRunsAsFailed() {
        PipelineRun zombie = newRun();
        zombie.advanceTo(PipelineRun.Status.BUILDING);
        zombie.addLog("[INFO] 多阶段构建中...");
        persistenceService.onChanged(zombie);

        persistenceService.markZombiesFailed();

        PipelineRunEntity entity = pipelineRunRepository.findByRunId(zombie.getId()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getErrorMessage()).contains("重启前中断");
        assertThat(entity.getLogs()).contains("重启前中断");
    }
}
