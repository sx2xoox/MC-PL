package io.github.sx2xoox.mcpl.game.superpower.support.world;

import com.destroystokyo.paper.SkinParts;

/**
 * {@link SkinParts} as the raw bit set the client sends (cape, jacket, left/right sleeve, left/right pants, hat),
 * since Paper's implementation lives in the server.
 */
public final class TestSkinParts implements SkinParts.Mutable {

    /** Every part shown, like a default client. */
    public static final int ALL = 0x7F;

    private int raw;

    public TestSkinParts(int raw) {
        this.raw = raw & ALL;
    }

    private boolean has(int bit) {
        return (raw & bit) != 0;
    }

    private void set(int bit, boolean enabled) {
        raw = enabled ? raw | bit : raw & ~bit;
    }

    @Override
    public boolean hasCapeEnabled() {
        return has(0x01);
    }

    @Override
    public boolean hasJacketEnabled() {
        return has(0x02);
    }

    @Override
    public boolean hasLeftSleeveEnabled() {
        return has(0x04);
    }

    @Override
    public boolean hasRightSleeveEnabled() {
        return has(0x08);
    }

    @Override
    public boolean hasLeftPantsEnabled() {
        return has(0x10);
    }

    @Override
    public boolean hasRightPantsEnabled() {
        return has(0x20);
    }

    @Override
    public boolean hasHatsEnabled() {
        return has(0x40);
    }

    @Override
    public int getRaw() {
        return raw;
    }

    @Override
    public SkinParts.Mutable mutableCopy() {
        return new TestSkinParts(raw);
    }

    @Override
    public void setCapeEnabled(boolean enabled) {
        set(0x01, enabled);
    }

    @Override
    public void setJacketEnabled(boolean enabled) {
        set(0x02, enabled);
    }

    @Override
    public void setLeftSleeveEnabled(boolean enabled) {
        set(0x04, enabled);
    }

    @Override
    public void setRightSleeveEnabled(boolean enabled) {
        set(0x08, enabled);
    }

    @Override
    public void setLeftPantsEnabled(boolean enabled) {
        set(0x10, enabled);
    }

    @Override
    public void setRightPantsEnabled(boolean enabled) {
        set(0x20, enabled);
    }

    @Override
    public void setHatsEnabled(boolean enabled) {
        set(0x40, enabled);
    }

    @Override
    public SkinParts immutableCopy() {
        return new TestSkinParts(raw);
    }
}
