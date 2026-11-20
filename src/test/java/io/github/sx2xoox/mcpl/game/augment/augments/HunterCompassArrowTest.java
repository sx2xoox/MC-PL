package io.github.sx2xoox.mcpl.game.augment.augments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class HunterCompassArrowTest {

    private static Location facing(float yaw) {
        return new Location(null, 0, 64, 0, yaw, 0);
    }

    @Test
    void arrowsAreRelativeToWhereTheOwnerLooks() {
        Location south = new Location(null, 0, 64, 10);
        assertEquals("↑", HunterCompassAugment.arrow(facing(0), south), "yaw 0 looks south");
        assertEquals("↓", HunterCompassAugment.arrow(facing(180), south));
        assertEquals("←", HunterCompassAugment.arrow(facing(90), south), "facing west, south is on the left");
        assertEquals("→", HunterCompassAugment.arrow(facing(-90), south), "facing east, south is on the right");
        assertEquals("↗", HunterCompassAugment.arrow(facing(0), new Location(null, -10, 64, 10)), "facing south, south-west is ahead right");
    }
}
