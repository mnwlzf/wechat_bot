package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.repository.PromptTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PromptTemplateService {
    private final PromptTemplateRepository repository;

    public PromptTemplateService(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    public List<PromptTemplate> list() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    public PromptTemplate get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("提示词模板不存在"));
    }

    @Transactional
    public PromptTemplate create(PromptTemplate input) {
        validate(input);
        input.setId(null);
        return repository.save(input);
    }

    @Transactional
    public PromptTemplate update(UUID id, PromptTemplate input) {
        validate(input);
        PromptTemplate template = get(id);
        template.setName(input.getName());
        template.setDescription(input.getDescription() == null ? "" : input.getDescription());
        template.setContent(input.getContent());
        return repository.save(template);
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    private void validate(PromptTemplate input) {
        if (input.getName() == null || input.getName().isBlank()) {
            throw ApiException.badRequest("模板名称不能为空");
        }
        if (input.getContent() == null || input.getContent().isBlank()) {
            throw ApiException.badRequest("模板内容不能为空");
        }
        if (input.getName().length() > 120) {
            throw ApiException.badRequest("模板名称不能超过 120 个字符");
        }
        if (input.getDescription() != null && input.getDescription().length() > 300) {
            throw ApiException.badRequest("模板说明不能超过 300 个字符");
        }
    }
}
