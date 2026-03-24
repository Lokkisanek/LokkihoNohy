package net.lokkisan.bazzarclient;

import java.util.Random;

public class MouseHelper {
    private static final Random random = new Random();

    public static long getWaitTime(int lastSlot, int newSlot) {
        if (lastSlot == -1) return 250 + random.nextInt(200);
        
        int row1 = lastSlot / 9;
        int col1 = lastSlot % 9;
        int row2 = newSlot / 9;
        int col2 = newSlot % 9;
        
        double distance = Math.sqrt(Math.pow(row1 - row2, 2) + Math.pow(col1 - col2, 2));
        // Čas simulující pohyb myši: vzdálenost * (40-80ms) + základní reakce
        return (long) (distance * (40 + random.nextInt(40))) + 150;
    }
}