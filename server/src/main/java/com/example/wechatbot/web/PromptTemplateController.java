package com.example.wechatbot.web;

import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.service.PromptTemplateService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 提示词模板：可复用的系统提示词，支持按会话绑定不同人格。
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
                template.getUpdatedAt()
        );
    }
}
