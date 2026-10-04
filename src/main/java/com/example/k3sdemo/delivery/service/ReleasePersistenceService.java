package com.example.k3sdemo.delivery.service;

import com.example.k3sdemo.delivery.entity.Artifact;
import com.example.k3sdemo.delivery.entity.Release;
import com.example.k3sdemo.delivery.entity.ReleaseStage;
import com.example.k3sdemo.delivery.repository.ArtifactRepository;
import com.example.k3sdemo.delivery.repository.ReleaseRepository;
import com.example.k3sdemo.delivery.repository.ReleaseStageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 发布持久化外壳（P2）。
 * 现有 ReleaseService 仍是执行引擎；本服务负责：
 * 创建发布时写 release + release_stage(6 行)，执行过程中按阶段推进更新库。
 */
@Service
public class ReleasePersistenceService {

    /** 抽屉进度条的 6 个固定阶段（设计 3.5） */
    private static final String[] STAGE_CODES = {
            "PR_CHECK", "BUILD", "PREVIEW", "BETA", "CANARY", "STABLE"
    };
    private static final String[] STAGE_NAMES = {
            "PR 检查", "构建制品", "Preview", "Beta 验收", "生产灰度", "稳定发布"
    };

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReleaseRepository releaseRepository;
    private final ReleaseStageRepository releaseStageRepository;
    private final ArtifactRepository artifactRepository;
    private final ActivityLogService activityLog;

    public ReleasePersistenceService(ReleaseRepository releaseRepository,
                                     ReleaseStageRepository releaseStageRepository,
                                     ArtifactRepository artifactRepository,
                                     ActivityLogService activityLog) {
        this.releaseRepository = releaseRepository;
        this.releaseStageRepository = releaseStageRepository;
        this.artifactRepository = artifactRepository;
        this.activityLog = activityLog;
    }

    public List<Release> list(String status) {
        if (status != null && !status.isBlank()) {
            return releaseRepository.findByStatusInOrderByCreatedAtDesc(List.of(status.trim().toUpperCase(Locale.ROOT)));
        }
        return releaseRepository.findAll();
    }

    public List<Release> listByApp(Long appId) {
        return releaseRepository.findByAppIdOrderByCreatedAtDesc(appId);
    }

    public Release get(Long id) {
        return releaseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发布不存在: " + id));
    }

    public List<ReleaseStage> stages(Long releaseId) {
        return releaseStageRepository.findByReleaseIdOrderByStageOrderAsc(releaseId);
    }

    /**
     * 创建发布记录：写 release + 6 行 stage。
     * env/strategy 合法性在这里校验；执行仍由调用方触发 ReleaseService。
     */
    @Transactional
    public Release create(Long appId, Long artifactId, String env, String strategy,
                          String note, String operator) {
        Artifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "制品不存在: " + artifactId));
        if (!artifact.getAppId().equals(appId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "制品不属于该应用");
        }
        String e = normalizeEnv(env);
        String st = normalizeStrategy(strategy);

        Release release = new Release();
        release.setReleaseNo(nextReleaseNo());
        release.setAppId(appId);
        release.setArtifactId(artifactId);
        release.setEnv(e);
        release.setStrategy(st);
        release.setStatus(Release.Status.PENDING.name());
        release.setCurrentTraffic(0);
        release.setNote(note);
        release.setOperator(operator);
        Release saved = releaseRepository.save(release);

        for (int i = 1; i <= STAGE_CODES.length; i++) {
            ReleaseStage stage = new ReleaseStage();
            stage.setReleaseId(saved.getId());
            stage.setStageOrder(i);
            stage.setStageCode(STAGE_CODES[i - 1]);
            stage.setStageName(STAGE_NAMES[i - 1]);
            stage.setStatus(ReleaseStage.Status.PENDING.name());
            releaseStageRepository.save(stage);
        }
        activityLog.log(appId, saved.getId(), "USER", operator, "RELEASE_CREATED",
                "创建发布 " + saved.getReleaseNo() + " · " + e, e);
        return saved;
    }

    /** 阶段推进：执行引擎回调。status: RUNNING/PASSED/FAILED/SKIPPED */
    @Transactional
    public void advanceStage(Long releaseId, int stageOrder, String status) {
        ReleaseStage stage = releaseStageRepository
                .findByReleaseIdAndStageOrder(releaseId, stageOrder)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "发布阶段不存在: release=" + releaseId + " order=" + stageOrder));
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("RUNNING", "PASSED", "FAILED", "SKIPPED").contains(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知阶段状态: " + status);
        }
        stage.setStatus(s);
        if ("RUNNING".equals(s)) {
            stage.setStartedAt(LocalDateTime.now());
        } else {
            stage.setFinishedAt(LocalDateTime.now());
        }
        releaseStageRepository.save(stage);

        Release release = get(releaseId);
        release.setCurrentStage(stage.getStageCode());
        if ("RUNNING".equals(s)) {
            release.setStatus(Release.Status.RUNNING.name());
            if (release.getStartedAt() == null) {
                release.setStartedAt(LocalDateTime.now());
            }
        } else if ("FAILED".equals(s)) {
            release.setStatus(Release.Status.FAILED.name());
            release.setFinishedAt(LocalDateTime.now());
        } else if ("PASSED".equals(s) && stageOrder == STAGE_CODES.length) {
            release.setStatus(Release.Status.SUCCESS.name());
            release.setFinishedAt(LocalDateTime.now());
        }
        releaseRepository.save(release);
        activityLog.log(release.getAppId(), releaseId, "SYSTEM", null, "STAGE_" + s,
                "阶段[" + stage.getStageName() + "] " + s, release.getEnv());
    }

    /** 业务编号：rel-20261005-018 风格（日期 + 当日序号） */
    private String nextReleaseNo() {
        String date = LocalDateTime.now().format(NO_FMT);
        String prefix = "rel-" + date + "-";
        long count = releaseRepository.count();
        return String.format("%s%03d", prefix, count + 1);
    }

    private String normalizeEnv(String env) {
        String e = env == null ? "" : env.trim().toUpperCase(Locale.ROOT);
        return switch (e) {
            case "PREVIEW", "BETA", "PROD", "PRODUCTION" -> "PRODUCTION".equals(e) ? "PROD" : e;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知环境: " + env);
        };
    }

    private String normalizeStrategy(String strategy) {
        String s = strategy == null ? "" : strategy.trim().toUpperCase(Locale.ROOT);
        if (!List.of("CANARY", "BLUE_GREEN", "DEPLOY_ONLY").contains(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知发布策略: " + strategy);
        }
        return s;
    }
}
