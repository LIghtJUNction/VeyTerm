package com.termux.app.files.decompile;

/** Compiled by javac together with the Java actually exported from the generated DEX. Host only. */
public final class ExportedJavaTest {
    public static void main(String[] args) {
        if (fixture.Demo.sum(10) != 45 || fixture.Demo.sum(0) != 0 || fixture.Demo.sum(-1) != 0
            || fixture.Demo.abs(-17) != 17 || fixture.Demo.abs(12) != 12
            || !fixture.Demo.greet().equals("你好 VeyTerm") || fixture.Demo.length("hello") != 5
            || fixture.Demo.flag() != 7 || !fixture.Demo.MESSAGE.equals("field marker")
            || fixture.Demo.safeDivide(9, 3) != 3 || fixture.Demo.safeDivide(9, 0) != -1
            || fixture.Outer.answer() != 99 || fixture.Outer.Inner.answer() != 99) throw new AssertionError("Decompiled Java semantics differ");
        System.out.println("PASS: exported Java compiles as Java 8 and executes 13 original DEX behavior checks");
    }
}
