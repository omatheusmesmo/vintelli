package dev.vintelli.spike;

public final class Debuggee {
    public static void main(String[] args) throws Exception {
        System.out.println(checkpoint(21));
        Thread.sleep(30_000);
    }
    static int checkpoint(int value) {
        int doubled = value * 2;
        return doubled;
    }
}
