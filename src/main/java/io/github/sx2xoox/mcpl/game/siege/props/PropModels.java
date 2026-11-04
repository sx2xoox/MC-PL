package io.github.sx2xoox.mcpl.game.siege.props;

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The display of the map prop models ({@code tools/assetgen/models/props.py} → {@code mcpl:prop/<id>}): each model's
 * display scale and the height of its centre above the prop's base, as designed. Kept in sync with {@code PROPS} of
 * props.py ({@code PropModelsTest} reads it).
 */
public final class PropModels {

    /** How a prop model is shown at size 1: item display scale and its centre's height above the base (blocks). */
    public record Display(float scale, double centre) {
    }

    static final Map<String, Display> DISPLAYS = Map.of(
            "prop/lollipop", new Display(4, 6.0),
            "prop/candy_cane", new Display(3, 4.5),
            "prop/train", new Display(4, 6.0),
            "prop/windup_car", new Display(3, 4.5),
            "prop/windup_car_key", new Display(3, 1.25),
            "prop/spinning_top", new Display(4, 6.0),
            "prop/boat", new Display(3, 4.0),
            "prop/duck", new Display(2, 1.5),
            "prop/rainbow", new Display(5, 4.0));

    private PropModels() {
    }

    /** The display of model {@code id}, or null for a model this table does not know. */
    public static @Nullable Display display(String id) {
        return DISPLAYS.get(id);
    }

    /** Every known prop model id. */
    public static Set<String> ids() {
        return DISPLAYS.keySet();
    }
}
