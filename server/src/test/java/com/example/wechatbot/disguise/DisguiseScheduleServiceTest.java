package com.example.wechatbot.disguise;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.AppSetting;
import com.example.wechatbot.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisguiseScheduleServiceTest {

    private AppSettingRepository repository;
    private DisguiseProperties properties;
    private DisguiseScheduleService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppSettingRepository.class);
        properties = new DisguiseProperties();
        service = new DisguiseScheduleService(repository, properties);
    }

    @Test
    void updatesAndSortsManualBlocks() {
        service.updateAwayBlocks(true, List.of("15:00-16:00", "09:00-09:30"));

        assertThat(properties.isAwayBlocksEnabled()).isTrue();
        assertThat(properties.getAwayBlocks()).containsExactly("09:00-09:30", "15:00-16:00");
        verify(repository, org.mockito.Mockito.times(2)).save(any(AppSetting.class));
    }

    @Test
    void allowsEmptyBlocksWhenSwitchIsOff() {
        service.updateAwayBlocks(false, List.of());

        assertThat(properties.isAwayBlocksEnabled()).isFalse();
        assertThat(properties.getAwayBlocks()).isEmpty();
    }

    @Test
    void rejectsOverlappingOrTooShortBlocks() {
        assertThatThrownBy(() -> service.updateAwayBlocks(true,
                List.of("23:00-01:00", "00:30-02:00")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能重叠");

        assertThatThrownBy(() -> service.updateAwayBlocks(true, List.of("10:00-10:03")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("至少");
    }

    @Test
    void loadsPersistedValues() {
        when(repository.findById(DisguiseScheduleService.KEY_AWAY_BLOCKS_ENABLED))
                .thenReturn(Optional.of(new AppSetting(DisguiseScheduleService.KEY_AWAY_BLOCKS_ENABLED, "false")));
        when(repository.findById(DisguiseScheduleService.KEY_AWAY_BLOCKS))
                .thenReturn(Optional.of(new AppSetting(DisguiseScheduleService.KEY_AWAY_BLOCKS,
                        "18:00-18:30,12:00-12:20")));

        service.reload();

        assertThat(properties.isAwayBlocksEnabled()).isFalse();
        assertThat(properties.getAwayBlocks()).containsExactly("12:00-12:20", "18:00-18:30");
    }
}
