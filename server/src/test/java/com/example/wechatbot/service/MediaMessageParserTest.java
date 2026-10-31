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
        // 文件带上大小，模型才知道「这是个大文件」而不是一句光秃秃的文件名
        assertThat(parsed.placeholder()).isEqualTo("[文件] 报价单.pdf · 512.0 KB");
        assertThat(parsed.name()).isEqualTo("报价单.pdf");
        assertThat(parsed.size()).isEqualTo(524288L);
    }

    @Test
    void linkKeepsTitleAndHttpUrl() {
        String xml = "<msg><appmsg><title><![CDATA[春季新品发布]]></title>"
                + "<url>https://example.com/a?b=1&amp;c=2</url><type>5</type></appmsg></msg>";
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);
        assertThat(parsed.kind()).isEqualTo(MediaKind.LINK);
        assertThat(parsed.placeholder())
                .startsWith("[链接] 春季新品发布")
                .contains("https://example.com/a?b=1&c=2");
        assertThat(parsed.url()).isEqualTo("https://example.com/a?b=1&c=2");
    }

    @Test
    void nonHttpUrlIsDropped() {
        String xml = "<msg><appmsg><title>小程序</title><url>weixin://dl/business</url></appmsg></msg>";
        assertThat(MediaMessageParser.parse(49, xml).url()).isNull();
    }

    @Test
    void unknownTypeFallsBackToPlaceholder() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(9999, "");
        assertThat(parsed.kind()).isEqualTo(MediaKind.OTHER);
        assertThat(parsed.placeholder()).isEqualTo("[消息 type=9999]");
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
    void quoteCardWithoutTextIsNotMistakenForALink() {
        String xml = "<msg><appmsg><title>可以，我十点到</title><type>57</type>"
                + "<refermsg><displayname>张三</displayname><content>明天上午十点开会</content></refermsg>"
                + "</appmsg></msg>";

        // 子类型 57 是引用卡片：没走 parseQuote 时只会退化成一句 [卡片]，不会把原话当链接标题
        assertThat(MediaMessageParser.parse(49, xml).kind()).isEqualTo(MediaKind.OTHER);
        assertThat(MediaMessageParser.parse(49, xml).placeholder()).startsWith("[卡片]");
        // 原文仍然要靠 parseQuote 才拿得到
        assertThat(MediaMessageParser.parseQuote(49, xml).quoteText()).isEqualTo("明天上午十点开会");
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
        assertThat(quote.quoteText()).isEqualTo("[文件] 报价单.pdf · 512.0 KB");
    }

    /**
     * 微信实际报文把 {@code <type>} 排在 {@code <content>} 之后（图片引用就是这样）。
     * 旧逻辑只看 {@code <content>} 之前的部分，于是把类型判成「文字」，把被引用图片的
     * XML 抽成 {@code eyJwaGFzaCI6…} 这样的 base64 垃圾串发给模型。
     */
    @Test
    void quotedImageWithTypeAfterContentIsNotMistakenForText() {
        String inner = "<?xml version=\"1.0\"?>\n<msg>\n\t<img aeskey=\"cdf424ba4ec560da35a05541e979b22b\""
                + " cdnthumbmd5=\"df19bbfd253240e3bf95962f27acd3f5\" md5=\"f04e8976d17cb87c510eef2344a4cc67\""
                + " length=\"376977\" />\n\t<secHashInfoBase64>eyJwaGFzaCI6IjUwMzA1MDcwMDAyMDMwMTAifQ==</secHashInfoBase64>"
                + "\n</msg>";
        String xml = "<msg><appmsg><title>@小助手 这个图片的内容是什么</title><type>57</type><refermsg>"
                + "<content>" + escape(inner) + "</content>"
                + "<createtime>1793346721</createtime>"
                + "<displayname> 陌年</displayname><fromusr>wxid_rsje67b7hozm22</fromusr>"
                + "<svrid>5434531821355315196</svrid>"
                + "<type>3</type><chatusr>wxid_rsje67b7hozm22</chatusr></refermsg></appmsg></msg>";

        MediaMessageParser.Quoted quote = MediaMessageParser.parseQuote(49, xml);

        assertThat(quote).isNotNull();
        assertThat(quote.quoteAuthor()).isEqualTo("陌年");
        assertThat(quote.quoteText()).isEqualTo("[图片]");
        assertThat(quote.hasQuotedImage()).isTrue();
        assertThat(quote.media().md5()).isEqualTo("f04e8976d17cb87c510eef2344a4cc67");
        assertThat(quote.media().thumbMd5()).isEqualTo("df19bbfd253240e3bf95962f27acd3f5");
        assertThat(quote.quoteEpochSeconds()).isEqualTo(1793346721L);
    }

    @Test
    void quotedVoiceWithTypeAfterContentKeepsDuration() {
        String inner = "wxid_5piziuygqi7c22:\n<msg><voicemsg voicelength=\"2159\" /></msg>";
        String xml = "<msg><appmsg><title>@小助手 这个语音说的什么</title><type>57</type><refermsg>"
                + "<content>" + escape(inner) + "</content><createtime>1793330890</createtime>"
                + "<displayname>Cold</displayname><type>34</type>"
                + "<chatusr>wxid_5piziuygqi7c22</chatusr></refermsg></appmsg></msg>";

        MediaMessageParser.Quoted quote = MediaMessageParser.parseQuote(49, xml);

        assertThat(quote).isNotNull();
        assertThat(quote.quoteAuthor()).isEqualTo("Cold");
        assertThat(quote.quoteText()).isEqualTo("[语音 2″]");
        assertThat(quote.hasQuotedImage()).isFalse();
    }

    /** 拼一条「引用 type 类型消息再回复」的报文；被引用内容按微信习惯做 XML 转义。 */
    private static MediaMessageParser.Quoted quoteOf(String replyText, int quotedType, String quotedInner) {
        String xml = "<msg><appmsg><title>" + replyText + "</title><type>57</type>"
                + "<refermsg><displayname>张三</displayname><chatusr>wxid_zhangsan</chatusr>"
                + "<type>" + quotedType + "</type><content>" + escape(quotedInner) + "</content></refermsg>"
                + "</appmsg></msg>";
        return MediaMessageParser.parseQuote(49, xml);
    }

    private static String escape(String xml) {
        return xml.replace("<", "&lt;").replace(">", "&gt;");
    }
}
