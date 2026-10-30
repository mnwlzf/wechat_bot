package com.example.wechatbot.disguise;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisguiseClockTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);

    private DisguiseProperties properties() {
        DisguiseProperties properties = new DisguiseProperties();
        properties.setZone(ZONE.getId());
        properties.setScheduleEnabled(true);
        properties.setQuietStart("00:00");
        properties.setQuietEnd("00:01");
        properties.setAwayBlocksEnabled(true);
        properties.setAwayBlocks(List.of("10:00-11:00"));
        return properties;
    }

    @Test
    void manualAwayBlockMakesClockAway() {
        DisguiseClock clock = new DisguiseClock(properties());

        assertEquals(DisguiseClock.State.AWAY, clock.state(at(10, 30)));
        assertEquals(DisguiseClock.State.ACTIVE, clock.state(at(11, 1)));
    }

    @Test
    void awaySwitchDisablesOnlyAwayBlocks() {
        DisguiseProperties properties = properties();
        properties.setAwayBlocksEnabled(false);
        DisguiseClock clock = new DisguiseClock(properties);

        assertEquals(DisguiseClock.State.ACTIVE, clock.state(at(10, 30)));
        assertEquals(DisguiseClock.State.ASLEEP, clock.state(at(0, 0).plusSeconds(1)));
    }

    @Test
    void manualAwayBlockSupportsOvernightRanges() {
        DisguiseProperties properties = properties();
        properties.setAwayBlocks(List.of("23:30-00:30"));
        DisguiseClock clock = new DisguiseClock(properties);

        assertEquals(DisguiseClock.State.AWAY, clock.state(at(23, 45)));
        assertEquals(DisguiseClock.State.AWAY,
                clock.state(ZonedDateTime.of(DAY.plusDays(1), LocalTime.of(0, 15), ZONE).toInstant()));
        assertEquals(DisguiseClock.State.ACTIVE,
                clock.state(ZonedDateTime.of(DAY.plusDays(1), LocalTime.of(0, 31), ZONE).toInstant()));
    }

    @Test
    void scheduleSwitchStillDisablesAllWindows() {
        DisguiseProperties properties = properties();
        properties.setScheduleEnabled(false);
        DisguiseClock clock = new DisguiseClock(properties);

        assertEquals(DisguiseClock.State.ACTIVE, clock.state(at(10, 30)));
        assertEquals(DisguiseClock.State.ACTIVE, clock.state(at(0, 0).plusSeconds(1)));
    }

    private static java.time.Instant at(int hour, int minute) {
        return ZonedDateTime.of(DAY, LocalTime.of(hour, minute), ZONE).toInstant();
    }
}
