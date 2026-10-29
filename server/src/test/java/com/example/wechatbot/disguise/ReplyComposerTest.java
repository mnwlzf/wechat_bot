package com.example.wechatbot.disguise;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyComposerTest {

    private final DisguiseProperties properties = new DisguiseProperties();
    private final ReplyComposer composer = new ReplyComposer(properties);

    @Test
    void splitsParagraphsIntoBubblesWithoutBlankLines() {
        String text = "在的\n\n明天上海具体下不下雨我查不到实时数据，不太确定\n\n"
                + "你打开手机天气看下“降水概率”，超过50%就带伞";

        List<String> parts = composer.split(text);

        assertThat(parts).hasSize(3);
        assertThat(parts).allSatisfy(part -> assertThat(part).doesNotContain("\n"));
    }

    @Test
    void keepsShortSingleLineAsOneBubble() {
        assertThat(composer.split("在的，稍等")).containsExactly("在的，稍等");
    }

    @Test
    void capsPartsAtConfiguredMaximum() {
        String text = "一\n二\n三\n四\n五\n六";

        List<String> parts = composer.split(text);

        assertThat(parts).hasSize(properties.getMaxParts());
    }
}
