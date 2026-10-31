package com.example.wechatbot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用消息 → {@link HookExtras} 的映射。
 *
 * <p>被引用的是一条图片时，光有「[图片]」这句占位没有用：得把被引用图片自己的 md5 和
 * 发送时间一起带下去，回复时才能去本地 {@code .dat} 里找到那张图。</p>
 */
class HookExtrasTest {

    @Test
    void quotedImageCarriesItsOwnMd5AndTimestamp() {
        MediaMessageParser.Parsed image = MediaMessageParser.parse(3,
                "<msg><img aeskey=\"cdf424ba4ec560da35a05541e979b22b\""
                        + " cdnthumbmd5=\"df19bbfd253240e3bf95962f27acd3f5\""
                        + " md5=\"f04e8976d17cb87c510eef2344a4cc67\"/></msg>");
        MediaMessageParser.Quoted quote = new MediaMessageParser.Quoted(
                "这个图片的内容是什么", "陌年", "[图片]", 1793346721L, image);

        HookExtras extras = HookExtras.of(quote, null);

        assertThat(extras.hasQuote()).isTrue();
        assertThat(extras.hasQuotedImage()).isTrue();
        assertThat(extras.quoteMd5Candidates())
                .containsExactly("f04e8976d17cb87c510eef2344a4cc67", "df19bbfd253240e3bf95962f27acd3f5");
        assertThat(extras.quoteAt()).isNotNull();
        assertThat(extras.quoteAt().toEpochSecond()).isEqualTo(1793346721L);
    }

    @Test
    void textQuoteHasNoImageKeys() {
        MediaMessageParser.Quoted quote = new MediaMessageParser.Quoted("我引用了什么", "无关人物", "6");

        HookExtras extras = HookExtras.of(quote, null);

        assertThat(extras.hasQuotedImage()).isFalse();
        assertThat(extras.quoteMd5Candidates()).isEmpty();
        assertThat(extras.quoteAt()).isNull();
    }

    @Test
    void plainImageMessageStillCarriesItsOwnKeys() {
        MediaMessageParser.Parsed image = MediaMessageParser.parse(3,
                "<msg><img md5=\"9f2c1b7a44e94f0d8b1c2d3e4f5a6b7c\"/></msg>");

        HookExtras extras = HookExtras.of(null, image);

        assertThat(extras.hasQuote()).isFalse();
        assertThat(extras.hasQuotedImage()).isFalse();
        assertThat(extras.md5Candidates()).containsExactly("9f2c1b7a44e94f0d8b1c2d3e4f5a6b7c");
    }
}
