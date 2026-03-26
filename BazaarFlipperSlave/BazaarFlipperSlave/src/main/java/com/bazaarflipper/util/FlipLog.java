package com.bazaarflipper.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class FlipLog {
    private static final int MAX_ENTRIES = 100;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final LinkedList<String> entries = new LinkedList<>();
    private double sessionProfit = 0;
    private int flipsCompleted = 0;

    public synchronized void log(String message) {
        String line = "[" + LocalTime.now().format(FMT) + "] " + message;
        entries.addLast(line);
        if (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }

    public synchronized void addProfit(double amount) {
        sessionProfit += amount;
        flipsCompleted++;
    }

    public synchronized List<String> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized double getSessionProfit() {
        return sessionProfit;
    }

    public synchronized int getFlipsCompleted() {
        return flipsCompleted;
    }

    public synchronized void reset() {
        entries.clear();
        sessionProfit = 0;
        flipsCompleted = 0;
    }
}
