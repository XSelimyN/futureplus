package com.futureplus.client;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A module setting shown in the ClickGui:
 *  BOOL  on/off
 *  NUM   slider (a choice list if labels != null: a click moves to the next one)
 *  COLOR color (RGB, picked with the HSV color wheel; saved as a number)
 * While the when(...) condition is false the setting is hidden and can't be changed (the ClickGui animates the row open/closed when the condition changes).
 */
public final class Setting {
    public enum Kind { BOOL, NUM, COLOR }

    public final String name;
    public final Kind kind;
    public final double min, max, step;
    public final String[] labels;   // choice list (for NUM), else null
    private final BooleanSupplier getBool;
    private final Consumer<Boolean> setBool;
    private final DoubleSupplier getNum;
    private final DoubleConsumer setNum;
    private BooleanSupplier visible = () -> true;

    private Setting(String name, Kind kind, double min, double max, double step, String[] labels,
                    BooleanSupplier gb, Consumer<Boolean> sb, DoubleSupplier gn, DoubleConsumer sn) {
        this.name = name; this.kind = kind; this.min = min; this.max = max; this.step = step; this.labels = labels;
        this.getBool = gb; this.setBool = sb; this.getNum = gn; this.setNum = sn;
    }

    public static Setting bool(String name, BooleanSupplier get, Consumer<Boolean> set) {
        return new Setting(name, Kind.BOOL, 0, 1, 1, null, get, set, null, null);
    }

    public static Setting num(String name, double min, double max, double step,
                              DoubleSupplier get, DoubleConsumer set) {
        return new Setting(name, Kind.NUM, min, max, step, null, null, null, get, set);
    }

    /** Choice list: left click forward, right click back. */
    public static Setting choice(String name, String[] labels, IntSupplier get, IntConsumer set) {
        return new Setting(name, Kind.NUM, 0, labels.length - 1, 1, labels, null, null,
            () -> get.getAsInt(), v -> set.accept((int) Math.round(v)));
    }

    /** Renk (0xRRGGBB). */
    public static Setting color(String name, IntSupplier get, IntConsumer set) {
        return new Setting(name, Kind.COLOR, 0, 0xFFFFFF, 1, null, null, null,
            () -> get.getAsInt(), v -> set.accept((int) Math.round(v) & 0xFFFFFF));
    }

    /** The setting is only visible while cond is true (the setting it depends on is on / the related mode is selected). */
    public Setting when(BooleanSupplier cond) { visible = cond; return this; }

    public boolean visible() { return visible.getAsBoolean(); }

    public boolean isChoice() { return labels != null; }

    public boolean getBool() { return getBool.getAsBoolean(); }
    public void setBool(boolean v) { setBool.accept(v); }
    public double getNum() { return getNum.getAsDouble(); }
    public int getColor() { return ((int) Math.round(getNum())) & 0xFFFFFF; }

    /** Rounds the value to the range and step, then writes it. */
    public void setNum(double v) {
        double snapped = Math.round((v - min) / step) * step + min;
        setNum.accept(Math.max(min, Math.min(max, snapped)));
    }

    /** Move d steps in the choice list (wrapping). */
    public void cycle(int d) {
        int n = labels.length;
        int cur = (int) Math.round(getNum());
        setNum(((cur + d) % n + n) % n);
    }

    public String display() {
        if (kind == Kind.BOOL) return getBool() ? "On" : "Off";
        if (kind == Kind.COLOR) return String.format("#%06X", getColor());
        double v = getNum();
        if (labels != null) {
            int i = Math.max(0, Math.min(labels.length - 1, (int) Math.round(v)));
            return labels[i];
        }
        return step >= 1.0 ? String.valueOf((int) Math.round(v)) : String.format(step < 0.01 ? "%.3f" : "%.2f", v);
    }
}
