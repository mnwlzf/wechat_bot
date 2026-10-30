package com.example.wechatbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentFilterServiceTest {
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
    private final ContentFilterService service = new ContentFilterService(settings);

    @Test
    void passesThroughWhenDisabled() {
        when(settings.filterMode()).thenReturn("off");
        when(settings.bannedWords()).thenReturn(List.of("违禁"));

        ContentFilterService.Result result = service.apply("这里有违禁词");

        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("这里有违禁词");
    }

    @Test
    void rewritesBannedWordsWithMask() {
        when(settings.filterMode()).thenReturn("rewrite");
        when(settings.bannedWords()).thenReturn(List.of("违禁", "敏感"));

        ContentFilterService.Result result = service.apply("违禁与敏感词都要处理");

        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("**与**词都要处理");
        assertThat(result.note()).contains("改写");
    }

    @Test
    void blocksWhenModeIsBlock() {
        when(settings.filterMode()).thenReturn("block");
        when(settings.bannedWords()).thenReturn(List.of("违禁"));

        ContentFilterService.Result result = service.apply("含违禁内容");

        assertThat(result.blocked()).isTrue();
        assertThat(result.note()).contains("拦截");
    }

    @Test
    void unmatchedContentIsUntouched() {
        when(settings.filterMode()).thenReturn("rewrite");
        when(settings.bannedWords()).thenReturn(List.of("违禁"));

        ContentFilterService.Result result = service.apply("正常回复");

        assertThat(result.content()).isEqualTo("正常回复");
        assertThat(result.blocked()).isFalse();
        assertThat(result.note()).isNull();
    }
}
