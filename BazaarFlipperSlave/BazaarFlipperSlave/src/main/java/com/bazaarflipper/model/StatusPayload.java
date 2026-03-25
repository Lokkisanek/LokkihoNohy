package com.bazaarflipper.model;

import java.util.List;

/**
 * Payload posted to the Python master's /api/status endpoint.
 * Serialised to JSON by TaskManager before sending.
 */
public class StatusPayload {

    /** Title of the currently open container GUI, or "none" */
    public String current_gui;

    /** Player's coin balance read from the scoreboard / stats */
    public double player_coins;

    /** List of ExtraAttributes.id strings currently in the player's inventory */
    public List<String> inventory_items;

    public StatusPayload(String gui, double coins, List<String> items) {
        this.current_gui = gui;
        this.player_coins = coins;
        this.inventory_items = items;
    }
}
