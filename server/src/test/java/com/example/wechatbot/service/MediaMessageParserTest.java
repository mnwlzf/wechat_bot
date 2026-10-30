package com.example.wechatbot.service;

import com.example.wechatbot.domain.MediaKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaMessageParserTest {
    @Test
    void imageBecomesPlaceholder() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(3, "");
        assertThat(parsed.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(parsed.placeholder()).isEqualTo("[图片]");
        assertThat(parsed.name()).isNull();
    }

    @Test
    void voiceCarriesDuration() {
        String xml = "<msg><voicemsg voicelength=\"4200\" /></msg>";
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(34, xml);
        assertThat(parsed.kind()).isEqualTo(MediaKind.VOICE);
        assertThat(parsed.placeholder()).isEqualTo("[语音 4″]");
    }

    @Test
    void fileExtractsNameAndSize() {
        String xml = "<msg><appmsg><title>报价单.pdf</title><type>6</type>"
                + "<appattach><totallen>524288</totallen><fileext>pdf</fileext></appattach></appmsg></msg>";
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);
        assertThat(parsed.kind()).isEqualTo(MediaKind.FILE);
        assertThat(parsed.placeholder()).isEqualTo("[文件] 报价单.pdf");
        assertThat(parsed.name()).isEqualTo("报价单.pdf");
        assertThat(parsed.size()).isEqualTo(524288L);
    }

    @Test
    void linkKeepsTitleAndHttpUrl() {
        String xml = "<msg><appmsg><title><![CDATA[春季新品发布]]></title>"
                + "<url>https://example.com/a?b=1&amp;c=2</url><type>5</type></appmsg></msg>";
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);
        assertThat(parsed.kind()).isEqualTo(MediaKind.LINK);
        assertThat(parsed.placeholder()).isEqualTo("[链接] 春季新品发布");
        assertThat(parsed.url()).isEqualTo("https://example.com/a?b=1&c=2");
    }

    @Test
    void nonHttpUrlIsDropped() {
        String xml = "<msg><appmsg><title>小程序</title><url>weixin://dl/business</url></appmsg></msg>";
        assertThat(MediaMessageParser.parse(49, xml).url()).isNull();
    }

    @Test
    void unknownTypeFallsBackToPlaceholder() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(48, "");
        assertThat(parsed.kind()).isEqualTo(MediaKind.OTHER);
        assertThat(parsed.placeholder()).isEqualTo("[消息 type=48]");
    }

    @Test
    void textTypeIsNotTreatedAsMedia() {
        assertThat(MediaMessageParser.isText(1)).isTrue();
        assertThat(MediaMessageParser.isText(3)).isFalse();
    }
}
