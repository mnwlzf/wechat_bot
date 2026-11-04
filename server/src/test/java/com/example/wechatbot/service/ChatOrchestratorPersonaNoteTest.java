package com.example.wechatbot.service;

import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「换了人设但语气没变」的另一半原因：窗口里几十条旧口吻的回复，模型会照着学。
 *
 * <p>所以换人设之后，只要窗口里还有旧回复，就要额外说一句「那些是旧人设说的，只当事实，别学语气」；
 * 等窗口里的消息全部晚于切换时间，这句话必须自动消失——否则会一直把模型往回拽。</p>
 */
class ChatOrchestratorPersonaNoteTest {

    private Conversation chat(OffsetDateTime switchedAt) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setChatId("wxid_demo");
        conversation.setChatType(ChatType.PRIVATE);
        conversation.setPersonaSwitchedAt(switchedAt);
        return conversation;
    }

    private MessageLog outbound(OffsetDateTime at) {
        MessageLog log = new MessageLog();
        log.setId(UUID.randomUUID());
        log.setDirection(MessageDirection.OUTBOUND);
        log.setContent("旧口吻的一句回复");
        log.setCreatedAt(at);
        return log;
    }

    private MessageLog inbound(OffsetDateTime at) {
        MessageLog log = new MessageLog();
        log.setId(UUID.randomUUID());
        log.setDirection(MessageDirection.INBOUND);
        log.setSenderName("深海");
        log.setContent("在吗");
        log.setCreatedAt(at);
        return log;
    }

    @Test
    void oldRepliesAfterASwitchGetFlagged() {
        OffsetDateTime now = OffsetDateTime.now();

        String note = ChatOrchestrator.personaSwitchNote(
                chat(now.minusMinutes(5)),
                List.of(inbound(now.minusMinutes(10)), outbound(now.minusMinutes(8)), inbound(now)));

        assertThat(note).isNotNull().contains("旧人设").contains("不要模仿");
    }

    @Test
    void nothingIsAddedWhenTheWindowIsAllAfterTheSwitch() {
        OffsetDateTime now = OffsetDateTime.now();

        assertThat(ChatOrchestrator.personaSwitchNote(
                chat(now.minusHours(2)),
                List.of(inbound(now.minusMinutes(10)), outbound(now.minusMinutes(8))))).isNull();
    }

    @Test
    void nothingIsAddedWhenThePersonaNeverChanged() {
        OffsetDateTime now = OffsetDateTime.now();

        assertThat(ChatOrchestrator.personaSwitchNote(
                chat(null), List.of(outbound(now.minusHours(1))))).isNull();
    }

    @Test
    void incomingMessagesAloneDoNotTriggerTheNote() {
        // 只有「我」说过的旧话会带跑语气；对方的消息不涉及我的人设
        OffsetDateTime now = OffsetDateTime.now();

        assertThat(ChatOrchestrator.personaSwitchNote(
                chat(now.minusMinutes(5)), List.of(inbound(now.minusMinutes(10))))).isNull();
    }
}
