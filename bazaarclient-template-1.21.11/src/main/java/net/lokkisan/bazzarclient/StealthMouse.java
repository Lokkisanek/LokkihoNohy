package net.lokkisan.bazzarclient;

import java.util.Random;

public class StealthMouse {
    private static final Random rand = new Random();

    // Využití přibližného Gamma rozdělení pro realističtější "human delay"
    public static long calculateHumanDelay(int fromSlot, int toSlot) {
        if (fromSlot == -1) return 400 + rand.nextInt(200);

        int x1 = fromSlot % 9, y1 = fromSlot / 9;
        int x2 = toSlot % 9, y2 = toSlot / 9;
        double distance = Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
        
        // Base travel time
        long travelTime = (long) (distance * (60 + rand.nextInt(30)));
        
        // Logaritmická křivka (simulace Gamma distribuce) pro "zaváhání"
        double hesitationChance = Math.random();
        long hesitation = (long) (Math.log(1 - hesitationChance) * -100); 
        
        travelTime += hesitation;

        // "Brain freeze" - člověk se občas zasekne
        if (rand.nextFloat() < 0.05) travelTime += 400 + rand.nextInt(400);

        return travelTime + 100; // + základní reakční doba
    }
}