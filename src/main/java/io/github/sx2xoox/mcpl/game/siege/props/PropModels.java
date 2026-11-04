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

    static final Map<String, Display> DISPLAYS = Map.ofEntries(
            Map.entry("prop/lollipop", new Display(4, 6.0)),
            Map.entry("prop/candy_cane", new Display(3, 4.5)),
            Map.entry("prop/train", new Display(4, 6.0)),
            Map.entry("prop/windup_car", new Display(3, 4.5)),
            Map.entry("prop/windup_car_key", new Display(3, 1.25)),
            Map.entry("prop/spinning_top", new Display(4, 6.0)),
            Map.entry("prop/boat", new Display(3, 4.0)),
            Map.entry("prop/duck", new Display(2, 1.5)),
            Map.entry("prop/rainbow", new Display(5, 4.0)),
            Map.entry("prop/cotton_tree", new Display(3, 4.5)),
            Map.entry("prop/crayon_box", new Display(3, 4.5)),
            Map.entry("prop/marble_jar", new Display(2, 3.0)),
            Map.entry("prop/block_stack", new Display(4, 4.0)),
            Map.entry("prop/toy_box", new Display(5, 4.5)),
            Map.entry("prop/toy_box_mirror", new Display(5, 4.5)),
            Map.entry("prop/toy_box_pink", new Display(3, 4.5)),
            Map.entry("prop/toy_box_sky", new Display(3, 4.5)),
            Map.entry("prop/toy_box_yellow", new Display(3, 4.5)),
            Map.entry("prop/jelly", new Display(2, 2.5)),
            Map.entry("prop/cheese", new Display(3, 4.5)),
            Map.entry("prop/cheese_key", new Display(3, 4.5)),
            Map.entry("prop/rabbit_watch", new Display(5, 5.0)),
            Map.entry("prop/rabbit_watch_hands", new Display(2, 5.6)),
            Map.entry("prop/sand_castle", new Display(2, 2.0)),
            Map.entry("prop/sand_bucket", new Display(2, 2.0)),
            Map.entry("prop/sand_spade", new Display(2, 2.5)),
            Map.entry("prop/sponge_bush", new Display(2, 2.5)));

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
