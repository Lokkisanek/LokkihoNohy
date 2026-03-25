# BazaarFlipperSlave — Fabric Client Mod

A client-side Fabric mod (MC 1.21.1) that acts as the **slave** in a
Master–Slave Bazaar flipping system.  All market logic lives in your Python
master; this mod polls it for tasks and executes them via GUI automation.

---

## Architecture

```
Python Master (localhost:8000)
        │
        │  GET /api/task  ◄─── poll every 1–2 s
        │  POST /api/status ──► player state
        │
        ▼
BazaarFlipperSlave (this mod)
  ├── TaskManager      — HTTP I/O (daemon thread)
  ├── ScreenTracker    — watches GUI title changes (Fabric ScreenEvents)
  └── TaskExecutor     — tick-driven state machine (game thread)
         ├── BUY flow
         ├── SELL flow
         ├── CLAIM_ORDERS flow
         └── CANCEL_ORDER flow
```

---

## File Structure

```
src/main/java/com/bazaarflipper/
├── BazaarFlipperMod.java       ← ClientModInitializer entry point
├── TaskManager.java            ← HTTP polling + status POST
├── ScreenTracker.java          ← GUI type detection via screen title
├── TaskExecutor.java           ← Multi-step state machine for all actions
├── model/
│   ├── Task.java               ← /api/task response POJO
│   └── StatusPayload.java      ← /api/status request POJO
├── mixin/
│   └── SignEditScreenMixin.java ← Auto-fills sign/anvil input dialogs
└── util/
    ├── NbtUtil.java            ← Reads ExtraAttributes.id from ItemStacks
    └── DelayUtil.java          ← Randomised anti-ban tick delays
```

---

## Setup

### Prerequisites
- JDK 21+
- Gradle (wrapper included)
- Fabric Loader ≥ 0.15 + Fabric API 0.102.x

### Build
```bash
./gradlew build
# Output: build/libs/bazaarflipper-1.0.0.jar
```
Copy the jar to your `.minecraft/mods/` folder.

### Python Master
Start your Python server on `http://localhost:8000` before launching Minecraft.

---

## API Contract

### GET /api/task
```json
{
  "action":      "BUY",
  "item_id":     "ENCHANTED_COBBLESTONE",
  "amount":      160,
  "price":       12345.6,
  "slot_target": -1
}
```
`action` values: `IDLE | BUY | SELL | CLAIM_ORDERS | CANCEL_ORDER`
`slot_target`: optional direct slot override (-1 = auto-scan)

### POST /api/status
```json
{
  "current_gui":    "Bazaar",
  "player_coins":   1234567.0,
  "inventory_items": ["ENCHANTED_COBBLESTONE", "ENCHANTED_LAPIS_LAZULI"]
}
```

---

## Task Execution Flows

### BUY / SELL
1. Wait for Bazaar main or item-list screen to be open
2. Scan slots for `item_id` (or use `slot_target` if provided)
3. Click item → wait for Product Info menu
4. Click slot 15 (Buy Order / Sell Offer)
5. Input amount (preset button or sign GUI)
6. Select price ("Top Order" button or sign GUI for custom)
7. Click slot 13 (Confirm) after a longer random pause

### CLAIM_ORDERS
1. Click slot 49 (Manage Orders) from Bazaar main
2. Scan order slots for lore containing "Filled"
3. Click filled order → click slot 10 (Claim)
4. Repeat until no more filled orders found

### CANCEL_ORDER
1. Open Manage Orders
2. Find order matching `item_id` (or use `slot_target`)
3. Right-click to cancel

---

## Anti-Ban Measures

| Measure | Implementation |
|---|---|
| Random action delay | 300–900 ms between every click / screen change |
| Extended confirm pause | 1 500–2 500 ms before clicking Confirm |
| No fixed timing | `ThreadLocalRandom` used for all delays |
| Human-scale poll rate | 1–2 s poll cadence with jitter |

---

## Slot Index Notes

Hypixel's Bazaar menus use **0-indexed** slots internally.  The project
specification lists some slots as 1-indexed (e.g., "slot 16 for Buy Order").
This mod corrects them to 0-indexed:

| Spec (1-indexed) | Mod (0-indexed) | Purpose |
|---|---|---|
| 50 | 49 | Manage Orders |
| 16 | 15 | Buy Order / Sell Offer |
| 11 | 10 | Claim Order |
| 13 | 13 | Confirm (already 0-indexed in spec) |

If Hypixel ever changes slot layouts, adjust the constants at the top of
`TaskExecutor.java`.

---

## NBT / Data Component Note

Minecraft 1.20.5+ moved item NBT into the **Data Component** system.
`NbtUtil.java` reads `DataComponentTypes.CUSTOM_DATA` (not the old
`stack.getNbt()`) to extract Hypixel's `ExtraAttributes.id`.

---

## Common Issues

| Symptom | Fix |
|---|---|
| Task never starts | Ensure Bazaar GUI is open before Python sends a non-IDLE task |
| "Item not found in GUI" | Add category navigation logic or provide `slot_target` |
| Sign input fails | Check that `SignEditScreen` field name matches Yarn mappings for your MC version |
| HTTP connection refused | Start the Python master before launching MC |
