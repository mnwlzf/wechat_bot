package com.example.wechatbot.web;

import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.service.PromptTemplateService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 人设（提示词模板）：支持自定义增删改、切换默认人设，并可按会话/群单独绑定。
 */
@RestController
@RequestMapping("/api/admin/prompt-templates")
public class PromptTemplateController {
    private final PromptTemplateService service;

    public PromptTemplateController(PromptTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminDtos.PromptTemplateView> list() {
        return service.list().stream().map(this::toView).toList();
    }

    @PostMapping
    public AdminDtos.PromptTemplateView create(@RequestBody AdminDtos.PromptTemplateInput input) {
        return toView(service.create(toEntity(input)));
    }

    @PutMapping("/{id}")
    public AdminDtos.PromptTemplateView update(@PathVariable UUID id,
                                               @RequestBody AdminDtos.PromptTemplateInput input) {
        return toView(service.update(id, toEntity(input)));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /** 设为全局默认人设：所有没有单独指定人设的会话都会用它。 */
    @PostMapping("/{id}/default")
    public AdminDtos.PromptTemplateView setDefault(@PathVariable UUID id) {
        return toView(service.setDefault(id));
    }

    /** 取消默认人设，回退到机器人自身的 system prompt。 */
    @DeleteMapping("/default")
    public void clearDefault() {
        service.setDefault(null);
    }

    private PromptTemplate toEntity(AdminDtos.PromptTemplateInput input) {
        PromptTemplate template = new PromptTemplate();
        template.setName(input.name());
        template.setDescription(input.description() == null ? "" : input.description());
        template.setContent(input.content());
        return template;
    }

    private AdminDtos.PromptTemplateView toView(PromptTemplate template) {
        return new AdminDtos.PromptTemplateView(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getContent(),
                template.isDefault(),
                template.getBuiltinKey() != null,
                template.getBuiltinKey(),
                template.getUpdatedAt()
        );
    }
}
