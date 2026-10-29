package com.example.wechatbot.ai;

import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.repository.AppSettingRepository;
import com.example.wechatbot.service.RuntimeSettingsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchAugmenterTest {

    private final AppProperties properties = new AppProperties();
    private final RuntimeSettingsService settings = new RuntimeSettingsService(
            mock(AppSettingRepository.class), new WeChatHookProperties(), properties);
    private final SearchAugmenter augmenter = new SearchAugmenter(null, properties, settings);

    @Test
    void skipsSmallTalk() {
        assertThat(augmenter.shouldSearch("你好")).isFalse();
        assertThat(augmenter.shouldSearch("在吗")).isFalse();
        assertThat(augmenter.shouldSearch("哈哈哈")).isFalse();
        assertThat(augmenter.shouldSearch("嗯嗯")).isFalse();
    }

    @Test
    void searchesFreshTopics() {
        assertThat(augmenter.shouldSearch("帮我查一下明天的天气")).isTrue();
        assertThat(augmenter.shouldSearch("今天有什么新闻")).isTrue();
        assertThat(augmenter.shouldSearch("特斯拉现在的股价")).isTrue();
        assertThat(augmenter.shouldSearch("2026年个税新政")).isTrue();
    }

    @Test
    void weakWordsNeedQuestionMark() {
        assertThat(augmenter.shouldSearch("今天怎么样")).isFalse();
        assertThat(augmenter.shouldSearch("今天怎么样？")).isTrue();
        assertThat(augmenter.shouldSearch("这个怎么弄")).isFalse();
        assertThat(augmenter.shouldSearch("这个报错是什么原因？")).isTrue();
    }

    @Test
    void returnsNullWhenSearchDisabled() {
        properties.getLlm().getSearch().setEnabled(false);
        assertThat(augmenter.buildContext(new BotConfig(), "帮我查一下明天的天气")).isNull();
    }

    @Test
    void returnsNullWhenHeuristicRejects() {
        assertThat(augmenter.buildContext(new BotConfig(), "在吗")).isNull();
    }

    @Test
    void runtimeToggleOverridesEnvDefault() {
        assertThat(settings.searchEnabled()).isTrue();
        settings.updateLlmPolicy(true, false);
        assertThat(settings.searchEnabled()).isFalse();
        assertThat(augmenter.buildContext(new BotConfig(), "帮我查一下明天的天气")).isNull();
    }

    @Test
    void resolvesModeSafely() {
        AppProperties.Search search = properties.getLlm().getSearch();
        assertThat(search.resolvedMode()).isEqualTo(AppProperties.Search.Mode.HEURISTIC);
        search.setMode("always");
        assertThat(search.resolvedMode()).isEqualTo(AppProperties.Search.Mode.ALWAYS);
        search.setMode("nonsense");
        assertThat(search.resolvedMode()).isEqualTo(AppProperties.Search.Mode.HEURISTIC);
        search.setEnabled(false);
        assertThat(search.resolvedMode()).isEqualTo(AppProperties.Search.Mode.OFF);
    }
}
