package com.example.wechatbot.service;

import com.example.wechatbot.domain.MediaKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 各类型微信消息的渲染规则（对应 {@code docs/messages.md} 的表格）。
 *
 * <p>这里用的报文都按微信真实结构写：appmsg 的子类型 {@code <type>} 有时排在 {@code <title>} 之后、
 * 链接卡片同样带 {@code <appattach>} 空壳、系统消息正文常常只有 wxid。这些坑都踩过，
 * 所以逐条钉死，避免以后改解析时又退回去。</p>
 */
class MediaMessageParserTypesTest {

    // ---------------------------------------------------------------- 基础类型

    @Test
    void textIsNeverTreatedAsMedia() {
        assertThat(MediaMessageParser.isText(1)).isTrue();
        assertThat(MediaMessageParser.isText(3)).isFalse();
    }

    @Test
    void imageKeepsMd5AndThumbKeys() {
        String xml = "<msg><img aeskey=\"cdf424ba4ec560da35a05541e979b22b\" encryver=\"1\""
                + " cdnthumbaeskey=\"df19bbfd253240e3bf95962f27acd3f5\""
                + " cdnthumbmd5=\"df19bbfd253240e3bf95962f27acd3f5\""
                + " cdnthumblength=\"23336\" length=\"376977\" md5=\"f04e8976d17cb87c510eef2344a4cc67\"/></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(3, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(parsed.placeholder()).isEqualTo("[图片]");
        assertThat(parsed.md5()).isEqualTo("f04e8976d17cb87c510eef2344a4cc67");
        assertThat(parsed.aeskey()).isEqualTo("cdf424ba4ec560da35a05541e979b22b");
        assertThat(parsed.thumbMd5()).isEqualTo("df19bbfd253240e3bf95962f27acd3f5");
        assertThat(parsed.size()).isEqualTo(376977L);
    }

    @Test
    void voiceOnlyExposesDuration() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(34,
                "<msg><voicemsg voicelength=\"5200\" length=\"3487\" /></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.VOICE);
        assertThat(parsed.placeholder()).isEqualTo("[语音 5″]");
    }

    @Test
    void voiceWithoutLengthStillSaysVoice() {
        assertThat(MediaMessageParser.parse(34, "").placeholder()).isEqualTo("[语音]");
    }

    @Test
    void videoKeepsDuration() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(43,
                "<msg><videomsg length=\"12\" playlength=\"12\" md5=\"abc\"/></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(parsed.placeholder()).isEqualTo("[视频 12″]");
    }

    @Test
    void stickerIsJustSticker() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(47,
                "<msg><emoji md5=\"226a3d4e6f8d09451ba4385b83d48c03\" len=\"313737\"/></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.STICKER);
        assertThat(parsed.placeholder()).isEqualTo("[表情]");
    }

    @Test
    void cardShowsNickname() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(42,
                "<msg bigheadimgurl=\"http://x\" username=\"wxid_abc\" nickname=\"张三\""
                        + " fullpy=\"zhangsan\" alias=\"zs\" sex=\"1\" />");

        assertThat(parsed.kind()).isEqualTo(MediaKind.CARD);
        assertThat(parsed.placeholder()).isEqualTo("[名片] 张三");
    }

    @Test
    void locationShowsPlaceNameAndAddress() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(48,
                "<msg><location x=\"39.98\" y=\"116.31\" scale=\"16\""
                        + " label=\"北京市海淀区中关村大街1号\" poiname=\"中关村\" maptype=\"0\" /></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.LOCATION);
        assertThat(parsed.placeholder()).isEqualTo("[位置] 中关村 · 北京市海淀区中关村大街1号");
    }

    @Test
    void locationWithoutPoiNameDoesNotRepeatItself() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(48,
                "<msg><location label=\"某个地方\" poiname=\"某个地方\" /></msg>");

        assertThat(parsed.placeholder()).isEqualTo("[位置] 某个地方");
    }

    @Test
    void callRecordIsRecognised() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(50, "<msg><voipmsg type=\"1\"/></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.CALL);
        assertThat(parsed.placeholder()).isEqualTo("[通话]");
    }

    @Test
    void channelMessageKeepsTitle() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(62,
                "<msg><appmsg><title>今天的日落</title></appmsg></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.CHANNEL);
        assertThat(parsed.placeholder()).isEqualTo("[视频号] 今天的日落");
    }

    // ---------------------------------------------------------------- appmsg 子类型

    @Test
    void linkCardKeepsTitleDescriptionAndUrl() {
        String xml = "<msg><appmsg><title>春季新品发布</title><des>限时八折，仅限三天</des>"
                + "<type>5</type><url>https://example.com/a?b=1&amp;c=2</url></appmsg></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.LINK);
        assertThat(parsed.placeholder())
                .startsWith("[链接] 春季新品发布")
                .contains("限时八折，仅限三天")
                .contains("https://example.com/a?b=1&c=2");
        assertThat(parsed.url()).isEqualTo("https://example.com/a?b=1&c=2");
    }

    /**
     * 小红书这类分享链接同样带 {@code <appattach>} 空壳（{@code totallen=0}、{@code fileext} 为空）。
     * 早先「有 appattach 就算文件」，结果整段分享文案被渲染成 {@code [文件] …}。
     */
    @Test
    void shareLinkWithEmptyAppattachIsNotAFile() {
        String xml = "<msg><appmsg><title>销冠在哪里都是销冠 #王安宇</title><des /><type>5</type>"
                + "<url>https://www.xiaohongshu.com/discovery/item/6ae30046000000001f004341</url>"
                + "<appattach><totallen>0</totallen><attachid /><fileext /><aeskey></aeskey></appattach>"
                + "</appmsg></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.LINK);
        assertThat(parsed.placeholder()).startsWith("[链接] 销冠在哪里都是销冠 #王安宇");
    }

    @Test
    void fileKeepsNameAndHumanReadableSize() {
        String xml = "<msg><appmsg><title>报价单.pdf</title><type>6</type>"
                + "<appattach><totallen>1572864</totallen><fileext>pdf</fileext></appattach></appmsg></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.FILE);
        assertThat(parsed.placeholder()).isEqualTo("[文件] 报价单.pdf · 1.5 MB");
        assertThat(parsed.size()).isEqualTo(1572864L);
    }

    @Test
    void fileWithoutSubtypeIsStillDetectedByItsFields() {
        String xml = "<msg><appmsg><title>预算表.xlsx</title>"
                + "<appattach><totallen>2048</totallen><fileext>xlsx</fileext></appattach></appmsg></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.FILE);
        assertThat(parsed.placeholder()).isEqualTo("[文件] 预算表.xlsx · 2.0 KB");
    }

    @Test
    void forwardedChatRecordKeepsTitle() {
        String xml = "<msg><appmsg><title>群聊的聊天记录</title><des>张三、李四</des>"
                + "<type>19</type><recorditem>base64…</recorditem></appmsg></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.RECORD);
        assertThat(parsed.placeholder()).isEqualTo("[聊天记录] 群聊的聊天记录 · 张三、李四");
    }

    @Test
    void miniProgramKeepsTitle() {
        String xml = "<msg><appmsg><title>滴滴出行</title><type>33</type>"
                + "<sourcedisplayname>微信团队</sourcedisplayname><url>weixin://dl/business</url></appmsg></msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.MINIAPP);
        assertThat(parsed.placeholder()).isEqualTo("[小程序] 滴滴出行");
        assertThat(parsed.url()).isNull();
    }

    @Test
    void newerMiniProgramSubtypeIsAlsoMiniProgram() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49,
                "<msg><appmsg><title>腾讯文档</title><type>36</type></appmsg></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.MINIAPP);
    }

    @Test
    void channelShareIsRecognised() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(49,
                "<msg><appmsg><title>我妈问我为什么跪着看手机</title><type>51</type></appmsg></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.CHANNEL);
        assertThat(parsed.placeholder()).isEqualTo("[视频号] 我妈问我为什么跪着看手机");
    }

    @Test
    void transferAndRedPacketAreLabelled() {
        MediaMessageParser.Parsed transfer = MediaMessageParser.parse(49,
                "<msg><appmsg><title>¥520.00</title><des>还你饭钱</des><type>2000</type></appmsg></msg>");
        MediaMessageParser.Parsed packet = MediaMessageParser.parse(49,
                "<msg><appmsg><title>恭喜发财，大吉大利</title><type>2001</type></appmsg></msg>");

        assertThat(transfer.kind()).isEqualTo(MediaKind.TRANSFER);
        assertThat(transfer.placeholder()).isEqualTo("[转账] ¥520.00 · 还你饭钱");
        assertThat(packet.kind()).isEqualTo(MediaKind.REDPACKET);
        assertThat(packet.placeholder()).isEqualTo("[红包] 恭喜发财，大吉大利");
    }

    @Test
    void musicAndLiveKeepTheirOwnLabels() {
        MediaMessageParser.Parsed music = MediaMessageParser.parse(49,
                "<msg><appmsg><title>晴天</title><type>3</type><url>https://y.qq.com/x</url></appmsg></msg>");
        MediaMessageParser.Parsed live = MediaMessageParser.parse(49,
                "<msg><appmsg><title>直播间：今晚八点</title><type>63</type></appmsg></msg>");

        assertThat(music.placeholder()).isEqualTo("[音乐] 晴天");
        assertThat(live.placeholder()).isEqualTo("[直播] 直播间：今晚八点");
    }

    // ---------------------------------------------------------------- 系统 / 未知

    @Test
    void systemMessageWithOnlyIdsIsCollapsed() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(10002,
                "wxid_ddwxepau0ei122 49878296302@chatroom wxid_x1yz3ff481hs22");

        assertThat(parsed.kind()).isEqualTo(MediaKind.SYSTEM);
        assertThat(parsed.placeholder()).isEqualTo("[系统消息]");
    }

    @Test
    void systemMessageWithTextKeepsText() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(10000, "你已加入群聊");

        assertThat(parsed.placeholder()).isEqualTo("[系统消息] 你已加入群聊");
    }

    @Test
    void unknownTypeKeepsTypeNumberAndTitle() {
        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(9999,
                "<msg><appmsg><title>奇怪的东西</title></appmsg></msg>");

        assertThat(parsed.kind()).isEqualTo(MediaKind.OTHER);
        assertThat(parsed.placeholder()).isEqualTo("[消息 type=9999] 奇怪的东西");
    }

    @Test
    void unknownTypeWithoutContentStillIdentifiable() {
        assertThat(MediaMessageParser.parse(9999, "").placeholder()).isEqualTo("[消息 type=9999]");
    }

    // ---------------------------------------------------------------- 引用里套各种类型

    @Test
    void quotedLinkKeepsItsTitle() {
        String inner = "<msg><appmsg><title>春季新品发布</title><des>限时八折</des><type>5</type>"
                + "<url>https://example.com/x</url></appmsg></msg>";

        MediaMessageParser.Quoted quote = quoteOf("这个链接有用吗", 49, inner);

        assertThat(quote).isNotNull();
        assertThat(quote.quoteText()).startsWith("[链接] 春季新品发布");
    }

    @Test
    void quotedLocationKeepsPlace() {
        String inner = "<msg><location label=\"上海市浦东新区世纪大道\" poiname=\"公司\" /></msg>";

        MediaMessageParser.Quoted quote = quoteOf("我发的位置对吗", 48, inner);

        assertThat(quote).isNotNull();
        assertThat(quote.quoteText()).isEqualTo("[位置] 公司 · 上海市浦东新区世纪大道");
    }

    /** 引用里再套引用：内层也要拆开，否则模型只看到一句 [卡片]。 */
    @Test
    void nestedQuoteIsUnwrappedOneMoreLevel() {
        String innerQuote = "<msg><appmsg><title>我也觉得</title><type>57</type><refermsg>"
                + "<displayname>李四</displayname><content>明天开会</content></refermsg></appmsg></msg>";

        MediaMessageParser.Quoted quote = quoteOf("你看他说的", 49, innerQuote);

        assertThat(quote).isNotNull();
        assertThat(quote.quoteText())
                .startsWith("[引用 李四：明天开会]")
                .contains("我也觉得");
    }

    /** 拼一条「引用 type 类型消息再回复」的报文；被引用内容按微信习惯做 XML 转义。 */
    private static MediaMessageParser.Quoted quoteOf(String replyText, int quotedType, String quotedInner) {
        String xml = "<msg><appmsg><title>" + replyText + "</title><type>57</type>"
                + "<refermsg><displayname>张三</displayname><chatusr>wxid_zhangsan</chatusr>"
                + "<content>" + escape(quotedInner) + "</content><type>" + quotedType + "</type>"
                + "</refermsg></appmsg></msg>";
        return MediaMessageParser.parseQuote(49, xml);
    }

    private static String escape(String xml) {
        return xml.replace("<", "&lt;").replace(">", "&gt;");
    }
}
