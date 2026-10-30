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

    @Test
    void imageExposesMd5AeskeyAndSize() {
        String xml = "<msg><img aeskey=\"00112233445566778899aabbccddeeff\" encryver=\"1\""
                + " cdnbigimgurl=\"http://cdn/x\" length=\"219229\""
                + " md5=\"9f2c1b7a44e94f0d8b1c2d3e4f5a6b7c\"/></msg>";
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(3, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(parsed.placeholder()).isEqualTo("[图片]");
        assertThat(parsed.md5()).isEqualTo("9f2c1b7a44e94f0d8b1c2d3e4f5a6b7c");
        assertThat(parsed.aeskey()).isEqualTo("00112233445566778899aabbccddeeff");
        assertThat(parsed.size()).isEqualTo(219229L);
    }

    @Test
    void quoteMessageSplitsUserTextAndQuotedContent() {
        String xml = "<msg><appmsg><title>可以，我十点到</title><type>57</type>"
                + "<refermsg><displayname>张三</displayname><chatusr>wxid_zhangsan</chatusr>"
                + "<type>1</type><content>明天上午十点开会</content></refermsg></appmsg></msg>";

        MediaMessageParser.Quoted quote = MediaMessageParser.parseQuote(49, xml);

        assertThat(quote).isNotNull();
        assertThat(quote.text()).isEqualTo("可以，我十点到");
        assertThat(quote.quoteAuthor()).isEqualTo("张三");
        assertThat(quote.quoteText()).isEqualTo("明天上午十点开会");
    }

    @Test
    void quoteWouldOtherwiseLookLikeALink() {
        String xml = "<msg><appmsg><title>可以，我十点到</title><type>57</type>"
                + "<refermsg><displayname>张三</displayname><content>明天上午十点开会</content></refermsg>"
                + "</appmsg></msg>";

        // 没有 <refermsg> 识别时会被当成链接，这正是要先用 parseQuote 的原因
        assertThat(MediaMessageParser.parse(49, xml).kind()).isEqualTo(MediaKind.LINK);
    }

    @Test
    void quotedXmlContentFallsBackToInnerTitle() {
        String inner = "<msg><appmsg><title>春季新品发布</title><type>5</type></appmsg></msg>";
        String xml = "<msg><appmsg><title>这个链接有用</title><type>57</type>"
                + "<refermsg><displayname>李四</displayname><content>" + inner + "</content></refermsg>"
                + "</appmsg></msg>";

        MediaMessageParser.Quoted quote = MediaMessageParser.parseQuote(49, xml);

        assertThat(quote).isNotNull();
        assertThat(quote.quoteAuthor()).isEqualTo("李四");
        assertThat(quote.quoteText()).isEqualTo("春季新品发布");
    }

    @Test
    void appMessageWithoutRefermsgIsNotAQuote() {
        String xml = "<msg><appmsg><title>普通链接</title><url>https://example.com</url></appmsg></msg>";

        assertThat(MediaMessageParser.parseQuote(49, xml)).isNull();
    }

    @Test
    void quoteWithoutTextIsNotAQuote() {
        String xml = "<msg><appmsg><title></title><refermsg><displayname>张三</displayname>"
                + "<content>原文</content></refermsg></appmsg></msg>";

        assertThat(MediaMessageParser.parseQuote(49, xml)).isNull();
    }

    @Test
    void plainTextIsNeverAQuote() {
        assertThat(MediaMessageParser.parseQuote(1, "你好")).isNull();
    }

    @Test
    void imageKeepsRealKeysApartFromThumbKeys() {
        String xml = "<msg><img aeskey=\"6f22c248aeed0eea892724bf57691483\" encryver=\"1\""
                + " cdnthumbaeskey=\"00112233445566778899aabbccddeeff\""
                + " cdnthumbmd5=\"89d861652a29abb6c5c9e8c188d62105\""
                + " length=\"219229\" md5=\"afff3bac26f7e382439d4eba57a9f191\"/></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(3, xml);

        assertThat(parsed.md5()).isEqualTo("afff3bac26f7e382439d4eba57a9f191");
        assertThat(parsed.aeskey()).isEqualTo("6f22c248aeed0eea892724bf57691483");
        assertThat(parsed.thumbMd5()).isEqualTo("89d861652a29abb6c5c9e8c188d62105");
        assertThat(parsed.thumbAeskey()).isEqualTo("00112233445566778899aabbccddeeff");
        assertThat(parsed.md5Candidates())
                .containsExactly("afff3bac26f7e382439d4eba57a9f191", "89d861652a29abb6c5c9e8c188d62105");
    }

    @Test
    void thumbAttributesFirstDoNotShadowRealKeys() {
        // 微信不同版本属性顺序不同：缩略图属性排在前面时，不能把它的后半截当成本体的 md5 / aeskey
        String xml = "<msg><img cdnthumbmd5=\"89d861652a29abb6c5c9e8c188d62105\""
                + " cdnthumbaeskey=\"00112233445566778899aabbccddeeff\""
                + " md5=\"afff3bac26f7e382439d4eba57a9f191\""
                + " aeskey=\"6f22c248aeed0eea892724bf57691483\"/></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(3, xml);

        assertThat(parsed.md5()).isEqualTo("afff3bac26f7e382439d4eba57a9f191");
        assertThat(parsed.aeskey()).isEqualTo("6f22c248aeed0eea892724bf57691483");
        assertThat(parsed.thumbMd5()).isEqualTo("89d861652a29abb6c5c9e8c188d62105");
    }

    @Test
    void imageWithoutThumbAttributesStillWorks() {
        String xml = "<msg><img aeskey=\"6f22c248aeed0eea892724bf57691483\""
                + " md5=\"afff3bac26f7e382439d4eba57a9f191\"/></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(3, xml);

        assertThat(parsed.thumbMd5()).isNull();
        assertThat(parsed.thumbAeskey()).isNull();
        assertThat(parsed.md5Candidates()).containsExactly("afff3bac26f7e382439d4eba57a9f191");
    }

    @Test
    void quotedImageBecomesPlaceholderInsteadOfXml() {
        String inner = "<msg><img aeskey=\"00112233445566778899aabbccddeeff\""
                + " md5=\"9f2c1b7a44e94f0d8b1c2d3e4f5a6b7c\"/></msg>";

        MediaMessageParser.Quoted quote = quoteOf("这图在哪拍的", 3, inner);

        assertThat(quote).isNotNull();
        assertThat(quote.text()).isEqualTo("这图在哪拍的");
        assertThat(quote.quoteText()).isEqualTo("[图片]");
    }

    @Test
    void quotedVoiceKeepsDuration() {
        MediaMessageParser.Quoted quote = quoteOf("刚才没听清", 34, "<msg><voicemsg voicelength=\"4200\" /></msg>");

        assertThat(quote).isNotNull();
        assertThat(quote.quoteText()).isEqualTo("[语音 4″]");
    }

    @Test
    void quotedStickerBecomesEmojiPlaceholder() {
        MediaMessageParser.Quoted quote = quoteOf("哈哈", 47, "<msg><emoji md5=\"abc\" /></msg>");

        assertThat(quote).isNotNull();
        assertThat(quote.quoteText()).isEqualTo("[表情]");
    }

    @Test
    void quotedFileKeepsFileName() {
        String inner = "<msg><appmsg><title>报价单.pdf</title><type>6</type>"
                + "<appattach><totallen>524288</totallen><fileext>pdf</fileext></appattach></appmsg></msg>";

        MediaMessageParser.Quoted quote = quoteOf("这个价格对吗", 49, inner);

        assertThat(quote).isNotNull();
        assertThat(quote.quoteText()).isEqualTo("[文件] 报价单.pdf");
    }

    /** 拼一条「引用 type 类型消息再回复」的报文；被引用内容按微信习惯做 XML 转义。 */
    private static MediaMessageParser.Quoted quoteOf(String replyText, int quotedType, String quotedInner) {
        String escaped = quotedInner.replace("<", "&lt;").replace(">", "&gt;");
        String xml = "<msg><appmsg><title>" + replyText + "</title><type>57</type>"
                + "<refermsg><displayname>张三</displayname><chatusr>wxid_zhangsan</chatusr>"
                + "<type>" + quotedType + "</type><content>" + escaped + "</content></refermsg>"
                + "</appmsg></msg>";
        return MediaMessageParser.parseQuote(49, xml);
    }
}
