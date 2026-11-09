package net.danh.storage.Utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumberFormatterTest {

    private static final String[] S = {"K", "M", "B", "T"};

    private static String f(double v) {
        return NumberFormatter.compact(v, 2, true, S);
    }

    @Test
    void thresholdsAndTrimming() {
        assertEquals("0", f(0));
        assertEquals("999", f(999));
        assertEquals("1K", f(1000));
        assertEquals("1.5K", f(1500));
        assertEquals("15.5K", f(15500));
        assertEquals("100K", f(100000));
        assertEquals("1.5M", f(1500000));
        assertEquals("123.12M", f(123123123));
        assertEquals("123.12B", f(123123123123d));
        assertEquals("1T", f(1000000000000d));
        assertEquals("1.25K", f(1250));
    }

    @Test
    void neverRoundsUpAcrossAUnit() {
        assertEquals("999.99K", f(999999));
        assertEquals("999.99M", f(999999999));
        assertEquals("1.14K", NumberFormatter.compact(1149.99, 2, true, S));
        assertEquals("1.15K", f(1150));
    }

    @Test
    void edgesAndOptions() {
        assertEquals("-1.5K", f(-1500));
        assertEquals("1000T", f(1e15));
        assertEquals("-", f(Double.NaN));
        assertEquals("-", f(Double.POSITIVE_INFINITY));
        assertEquals("1.00K", NumberFormatter.compact(1000, 2, false, S));
        assertEquals("1K", NumberFormatter.compact(1999, 0, true, S));
        assertEquals("1.5k", NumberFormatter.compact(1500, 2, true, new String[]{"k", "m", "b", "t"}));
        assertEquals("12.5", f(12.5));
    }
}
