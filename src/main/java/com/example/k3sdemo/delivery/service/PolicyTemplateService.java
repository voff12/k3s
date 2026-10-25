package com.example.k3sdemo.delivery.service;

import com.example.k3sdemo.delivery.dto.PolicyTemplateConfig;
import com.example.k3sdemo.delivery.entity.PolicyTemplate;
import com.example.k3sdemo.delivery.repository.ApplicationRepository;
import com.example.k3sdemo.delivery.repository.PolicyTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发布策略模板自定义（设计 3.2/3.4）：增/克隆/改/软删。
 * 内置三档（builtin=true）仅可改 name/description/config，不可删、不可改 code。
 */
@Service
public class PolicyTemplateService {

    /** 自定义 code 统一前缀，与内置 LOW/STANDARD/HIGH 区分 */
    static final String CUSTOM_CODE_PREFIX = "CUSTOM_";

    private final PolicyTemplateRepository repository;
    private final ApplicationRepository applicationRepository;
    private final ActivityLogService activityLog;
    private final ObjectMapper objectMapper;

    public PolicyTemplateService(PolicyTemplateRepository repository,
                                 ApplicationRepository applicationRepository,
                                 ActivityLogService activityLog,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.applicationRepository = applicationRepository;
        this.activityLog = activityLog;
        this.objectMapper = objectMapper;
    }

    /** 未删除的模板列表（内置 + 自定义），settings 页与创建应用下拉共用。 */
    public List<PolicyTemplate> list() {
        return repository.findAllByDeletedFalseOrderByCreatedAtAsc();
    }

    public PolicyTemplate get(Long id) {
        return repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "策略模板不存在: " + id));
    }

    /** 解析 configJson 为结构化配置；坏数据兜底为默认配置（不阻塞发布）。 */
    public PolicyTemplateConfig parse(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return new PolicyTemplateConfig();
        }
        try {
            return objectMapper.readValue(configJson, PolicyTemplateConfig.class);
        } catch (Exception e) {
            return new PolicyTemplateConfig();
        }
    }

    /** 按应用取生效策略；应用未选/模板被删 → null（调用方回落直通）。 */
    public PolicyTemplateConfig resolveForApp(Long appId) {
        if (appId == null) return null;
        return applicationRepository.findById(appId)
                .map(app -> app.getPolicyTemplateId())
                .filter(id -> id != null)
                .flatMap(repository::findByIdAndDeletedFalse)
                .map(t -> parse(t.getConfigJson()))
                .orElse(null);
    }

    @Transactional
    public PolicyTemplate create(CreateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称不能为空");
        }
        PolicyTemplateConfig config = req.config() != null ? req.config() : new PolicyTemplateConfig();
        config.validate();
        String code = generateCode(req.name());
        PolicyTemplate row = new PolicyTemplate();
        row.setName(req.name().trim());
        row.setCode(code);
        row.setDescription(req.description());
        row.setConfigJson(writeJson(config));
        row.setBuiltin(false);
        row.setDeleted(false);
        PolicyTemplate saved = repository.save(row);
        activityLog.log(null, null, "USER", req.operator(), "CREATE_POLICY_TEMPLATE",
                "创建发布策略模板 " + saved.getName(), null);
        return saved;
    }

    /** 克隆内置/自定义模板为自定义起点，name 加后缀，code 重新生成。 */
    @Transactional
    public PolicyTemplate clone(Long id, String operator) {
        PolicyTemplate source = get(id);
        CreateRequest req = new CreateRequest(
                source.getName() + "-副本",
                source.getDescription(),
                parse(source.getConfigJson()),
                operator);
        PolicyTemplate saved = create(req);
        activityLog.log(null, null, "USER", operator, "CLONE_POLICY_TEMPLATE",
                "克隆策略模板 " + source.getName() + " → " + saved.getName(), null);
        return saved;
    }

    @Transactional
    public PolicyTemplate update(Long id, CreateRequest req) {
        PolicyTemplate row = get(id);
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称不能为空");
        }
        PolicyTemplateConfig config = req.config() != null ? req.config() : new PolicyTemplateConfig();
        config.validate();
        row.setName(req.name().trim());
        row.setDescription(req.description());
        row.setConfigJson(writeJson(config));
        PolicyTemplate saved = repository.save(row);
        activityLog.log(null, null, "USER", req.operator(), "UPDATE_POLICY_TEMPLATE",
                "更新策略模板 " + saved.getName(), null);
        return saved;
    }

    /** 软删除。内置不可删；被应用引用 → 409 并提示引用数。 */
    @Transactional
    public PolicyTemplate delete(Long id, String operator) {
        PolicyTemplate row = get(id);
        if (Boolean.TRUE.equals(row.getBuiltin())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "内置模板不可删除: " + row.getCode());
        }
        long referencing = applicationRepository.countByPolicyTemplateId(id);
        if (referencing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "模板已被 " + referencing + " 个应用引用，先解除引用再删除");
        }
        row.setDeleted(true);
        PolicyTemplate saved = repository.save(row);
        activityLog.log(null, null, "USER", operator, "DELETE_POLICY_TEMPLATE",
                "删除策略模板 " + saved.getName(), null);
        return saved;
    }

    /** code 由时间戳生成（CUSTOM_ + 8 位 36 进制 = 15 字符 ≤ 列宽 16），碰撞时重掷。 */
    private String generateCode(String name) {
        String code = CUSTOM_CODE_PREFIX + nextCodeSuffix();
        while (repository.findByCode(code).isPresent()) {
            code = CUSTOM_CODE_PREFIX + nextCodeSuffix();
        }
        return code;
    }

    private String nextCodeSuffix() {
        String full = Long.toString(System.nanoTime(), 36).toUpperCase();
        return full.length() > 8 ? full.substring(full.length() - 8) : full;
    }

    private String writeJson(PolicyTemplateConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置序列化失败", e);
        }
    }

    public record CreateRequest(String name, String description, PolicyTemplateConfig config, String operator) { }
}
