package com.example.wechatbot.web;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.ChatMemory;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.service.ChatMemoryService;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 会话（群）共享记忆：查看滚动摘要、维护人工要点、手动触发一次摘要。
 *
 * <p>记忆按 chat_id 存，同一个群的所有成员共用同一份；私聊则只属于那一个人。</p>
 */
@RestController
@RequestMapping("/api/admin/memories")
public class MemoryController {
    private final ChatMemoryService memoryService;
    private final ConversationRepository conversationRepository;
    private final BotConfigRepository botRepository;

    public MemoryController(ChatMemoryService memoryService,
                            ConversationRepository conversationRepository,
                            BotConfigRepository botRepository) {
        this.memoryService = memoryService;
        this.conversationRepository = conversationRepository;
        this.botRepository = botRepository;
    }

    @GetMapping
    public List<AdminDtos.MemoryView> list(@RequestParam(required = false) String chatId) {
        return memoryService.list(chatId).stream().map(this::toView).toList();
    }

    @PostMapping
    public AdminDtos.MemoryView create(@RequestBody AdminDtos.MemoryInput input) {
        return toView(memoryService.createNote(
                input.chatId(), input.chatName(), input.content(), input.enabled()));
    }

    @PutMapping("/{id}")
    public AdminDtos.MemoryView update(@PathVariable UUID id,
                                       @RequestBody AdminDtos.MemoryUpdateInput input) {
        return toView(memoryService.update(id, input.content(), input.enabled()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        memoryService.delete(id);
    }

    /** 立即按现有历史重算一次摘要（同步返回结果，页面上能看到新内容）。 */
    @PostMapping("/summaries")
    public AdminDtos.MemoryView summarize(@RequestParam String chatId) {
        Conversation conversation = conversationRepository.findFirstByChatIdOrderByLastMessageAtDesc(chatId)
                .orElseThrow(() -> ApiException.notFound("该会话还没有消息记录，先聊几句再生成摘要"));
        BotConfig bot = botRepository.findById(conversation.getBotId())
                .orElseThrow(() -> ApiException.badRequest("会话所属的机器人配置不存在"));
        ChatMemory summary = memoryService.summarizeNow(bot, conversation);
        if (summary == null) {
            throw ApiException.badRequest("没有可压缩的历史消息（或摘要功能已关闭）");
        }
        return toView(summary);
    }

    /** 清掉某个会话的摘要，下次重新从头压缩；人工要点不受影响。 */
    @DeleteMapping("/summaries")
    public void resetSummary(@RequestParam String chatId) {
        memoryService.resetSummary(chatId);
    }

    private AdminDtos.MemoryView toView(ChatMemory memory) {
        return new AdminDtos.MemoryView(
                memory.getId(),
                memory.getChatId(),
                memory.getChatName(),
                memory.getKind().name(),
                memory.getSource().name(),
                memory.getContent(),
                memory.isEnabled(),
                memory.getMessageCount(),
                memory.getCoveredUntil(),
                memory.getCreatedAt(),
                memory.getUpdatedAt()
        );
    }
}
