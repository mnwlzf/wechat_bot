package com.example.wechatbot.web;

import com.example.wechatbot.service.PersonaBindingService;
import com.example.wechatbot.web.dto.AdminDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 人设绑定：按群（@chatroom）或按人（私聊 wxid）指定提示词。
 *
 * <p>与 {@code /conversations/{id}/persona} 的区别是这里只要 chatId 就能绑，
 * 不用先有会话记录；另外提供生效预览，方便排查「改了人设但回复没变」。</p>
 */
@RestController
@RequestMapping("/api/admin/personas")
public class PersonaBindingController {
    private final PersonaBindingService service;

    public PersonaBindingController(PersonaBindingService service) {
        this.service = service;
    }

    /** 绑定/解绑：promptTemplateId 传 null 表示回到默认人设。 */
    @PostMapping("/bind")
    public AdminDtos.ConversationView bind(@Valid @RequestBody AdminDtos.PersonaBindRequest request) {
        return service.bind(request.chatId(), request.promptTemplateId());
    }

    /** 该会话实际会发给模型的提示词：人设来源 + 人设正文 + 记忆块 + 风格约束。 */
    @GetMapping("/preview/{conversationId}")
    public AdminDtos.PersonaPreview preview(@PathVariable UUID conversationId) {
        return service.preview(conversationId);
    }
}
