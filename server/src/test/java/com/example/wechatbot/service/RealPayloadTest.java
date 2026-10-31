package com.example.wechatbot.service;

import com.example.wechatbot.domain.MediaKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用线上真实抓到的报文做回归（来自 {@code GET /api/admin/wechat/hook/recent} 的回调原文）。
 *
 * <p>合成报文容易写得比真实报文干净：真实报文里有 BOM、有 {@code <msgsource>} 这种转义再转义的
 * 嵌套 XML、有 {@code cdnurl}/{@code encrypturl} 这类大段 URL。这几条就是把它们钉住。</p>
 */
class RealPayloadTest {

    /** 群里「引用一条消息再回复」：子类型 57，refermsg 里 type 在 content 之前。 */
    @Test
    void groupQuoteReply() {
        String xml = """
                <?xml version="1.0"?>
                <msg>
                	<appmsg>
                		<title>我也觉得</title>
                		<action>view</action>
                		<type>57</type>
                		<finderLiveProductShare>
                			<isPriceBeginShow>false</isPriceBeginShow>
                		</finderLiveProductShare>
                		<gameshare>
                			<appbrandext>
                				<priority>-1</priority>
                			</appbrandext>
                			<duration>-1</duration>
                		</gameshare>
                		<refermsg>
                			<type>1</type>
                			<svrid>7736955256651060437</svrid>
                			<fromusr>45932803301@chatroom</fromusr>
                			<chatusr>wxid_ut87t5gjryum22</chatusr>
                			<displayname>望川秋水</displayname>
                			<content>
                不应该删完删彻底呢</content>
                			<msgsource>&lt;msgsource&gt;
                	&lt;pua&gt;1&lt;/pua&gt;
                	&lt;silence&gt;1&lt;/silence&gt;
                &lt;/msgsource&gt;
                </msgsource>
                			<createtime>1793367037</createtime>
                		</refermsg>
                		<appattach />
                	</appmsg>
                	<fromusername>wxid_a1bzukmmbqx122</fromusername>
                	<scene>0</scene>
                	<appinfo>
                		<version>1</version>
                		<appname />
                	</appinfo>
                	<commenturl />
                </msg>
                """;

        MediaMessageParser.Quoted quote = MediaMessageParser.parseQuote(49, xml);

        assertThat(quote).isNotNull();
        assertThat(quote.text()).isEqualTo("我也觉得");
        assertThat(quote.quoteAuthor()).isEqualTo("望川秋水");
        assertThat(quote.quoteText()).isEqualTo("不应该删完删彻底呢");
        assertThat(quote.quoteEpochSeconds()).isEqualTo(1793367037L);
        assertThat(quote.hasQuotedImage()).isFalse();
        // 引用卡片本身不能被当成链接（它没有 <url>，但有 <action>view</action>）
        assertThat(MediaMessageParser.parse(49, xml).kind()).isEqualTo(MediaKind.OTHER);
    }

    /** 表情：报文里带 cdnurl/encrypturl 一长串，渲染仍然只报一句 [表情]。 */
    @Test
    void stickerWithCdnUrls() {
        String xml = "<msg><emoji fromusername = \"wxid_9357603577522\" tousername = \"49878296302@chatroom\""
                + " type=\"2\" idbuffer=\"media:0_0\" md5=\"d9419fd6d8de7b7b697b116411ca7655\" len = \"14492\""
                + " cdnurl = \"http://vweixinf.tc.qq.com/110/20401/stodownload?m=d9419fd6d8de7b7b697b116411ca7655"
                + "&amp;filekey=3043020101042f302d02016e04025348042064&amp;hy=SH&amp;ef=1&amp;bizid=1022\""
                + " aeskey= \"89ffe857ffcd4820b8d1a4fcec8d3470\" width= \"378\" height= \"290\" desc= \"\" ></emoji>  </msg>";

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(47, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.STICKER);
        assertThat(parsed.placeholder()).isEqualTo("[表情]");
    }

    /** type=51 是微信自己的 op 通知（lastMessage 游标），别把 JSON 当正文塞给模型。 */
    @Test
    void internalOpNotification() {
        String xml = """
                <msg>
                <op id='5'>
                <username>weixin</username>
                <name>lastMessage</name>
                <arg>{"messageSvrId":"7770421653454383649","MsgCreateTime":"1793367436000"}</arg>
                </op>
                </msg>
                """;

        MediaMessageParser.Parsed parsed = MediaMessageParser.parse(51, xml);

        assertThat(parsed.kind()).isEqualTo(MediaKind.SYSTEM);
        assertThat(parsed.placeholder()).isEqualTo("[系统消息]");
    }
}
