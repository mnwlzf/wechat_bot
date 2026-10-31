package com.example.wechatbot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用消息在提示词里的排版。
 *
 * <p>这段文本直接决定模型会不会回答「我看不到具体内容」：单行 {@code [引用 张三：6]} 会被当成
 * 读不懂的标记，分成「被引用原文」和「我这次说的话」两段后模型才会照实复述。</p>
 */
class ChatOrchestratorQuoteBlockTest {

    @Test
    void quoteAndReplyAreSeparatedIntoTwoBlocks() {
        String block = ChatOrchestrator.quoteBlock("无关人物", "6", "@小助手 我引用了什么消息", false);

        assertThat(block).isEqualTo("""
                【引用的消息｜来自 无关人物】
                6
                【我这次说的话】
                @小助手 我引用了什么消息""");
        // 被引用的原文必须原样出现，不能退化成「引用」两个字
        assertThat(block).contains("无关人物");
        assertThat(block).doesNotContain("[引用 ");
    }

    @Test
    void quotedImageSaysThePictureIsAttached() {
        String block = ChatOrchestrator.quoteBlock("陌年", "[图片]", "@小助手 这个图片的内容是什么", true);

        assertThat(block).contains("[图片]");
        assertThat(block).contains("已附在本条消息里");
    }

    @Test
    void missingAuthorFallsBackToNeutralWording() {
        assertThat(ChatOrchestrator.quoteBlock(null, "原文", "在吗", false))
                .startsWith("【引用的消息｜来自 对方】");
        assertThat(ChatOrchestrator.quoteBlock("  ", "原文", "在吗", false))
                .startsWith("【引用的消息｜来自 对方】");
    }

    @Test
    void longQuoteIsTrimmedToKeepPromptSmall() {
        String longQuote = "很长的原文".repeat(400);

        String block = ChatOrchestrator.quoteBlock("张三", longQuote, "收到", false);

        String quotedPart = block.substring(block.indexOf('\n') + 1, block.indexOf("【我这次说的话】") - 1);
        assertThat(quotedPart).hasSize(800);
        assertThat(block).endsWith("【我这次说的话】\n收到");
    }

    @Test
    void replyTextIsKeptVerbatim() {
        String reply = "@小助手 帮我看看\n第二行也要保留";

        assertThat(ChatOrchestrator.quoteBlock("张三", "原文", reply, false))
                .endsWith("【我这次说的话】\n" + reply);
    }
}
