import requests, time, threading, os, uvicorn, random, json
import numpy as np
import torch
from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel
from stable_baselines3 import PPO
from typing import List, Optional
from datetime import datetime
import logging

# --- KONFIGURACE ---
BAZAAR_API  = "https://api.hypixel.net/v2/skyblock/bazaar"
MODEL_PATH  = "bazaar_scalper_brain_v3.zip"
STATE_FILE  = "bot_state.json"          # FIX 1.3 — persistence
TAX         = 0.0125

device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")

app    = FastAPI()
logger = logging.getLogger("BazaarOverlord")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

# ==============================================================================
#  HELPER — definováno PŘED BotState (FIX 1.1 — odstraněna duplicita)
# ==============================================================================

def _idle_task() -> dict:
    return {"action": "IDLE", "item_id": "", "amount": 0, "price": 0.0, "slot_target": -1}


def _build_task_for_item(bot_ref, best: dict, action: str = "BUY") -> dict:
    price = best["buy_p"] if action == "BUY" else best["sell_p"]
    qty   = max(1, int((bot_ref.cash * 0.18) / max(price, 0.01)))
    return {
        "action":     action,
        "item_id":    best["id"],
        "amount":     qty,
        "price":      round(price, 1),
        "sell_price": round(best["sell_p"], 1),
        "slot_target": -1,
    }


# ==============================================================================
#  PERSISTENCE (FIX 1.3)
# ==============================================================================

def save_state(bot):
    try:
        with open(STATE_FILE, "w") as f:
            json.dump({
                "cash":          bot.cash,
                "total_profit":  bot.total_profit,
                "active_orders": bot.active_orders,
                "mode":          bot.mode,
                "focus_item":    bot.focus_item,
                "is_running":    bot.is_running,
            }, f, indent=2)
    except Exception as e:
        logger.error(f"save_state failed: {e}")


def load_state(bot):
    if not os.path.exists(STATE_FILE):
        return
    try:
        with open(STATE_FILE) as f:
            data = json.load(f)
        bot.cash          = data.get("cash",          10_000_000)
        bot.total_profit  = data.get("total_profit",  0)
        bot.active_orders = data.get("active_orders", {})
        bot.mode          = data.get("mode",          "BALANCED")
        bot.focus_item    = data.get("focus_item",    "")
        # is_running intentionally NOT restored — always start paused
        logger.info(f"State loaded from {STATE_FILE}: cash={bot.cash:,.0f}, orders={len(bot.active_orders)}")
    except Exception as e:
        logger.error(f"load_state failed: {e}")


# ==============================================================================
#  BOT STATE
# ==============================================================================

class BotState:
    def __init__(self):
        self.is_running   = False
        self.mode         = "BALANCED"
        self.focus_item   = ""
        self.cash         = 10_000_000.0
        self.initial_cash = 10_000_000.0
        self.total_profit = 0
        self.active_orders: dict = {}
        self.market_cache: list  = []

        # Stav z Java módu
        self.mc_gui:       str        = "none"
        self.mc_coins:     float      = 0.0
        self.mc_inventory: List[str]  = []
        self.last_status_ts: float    = 0.0

        # Task systém
        self._task_queue:   list = []
        self._current_task: dict = _idle_task()
        self._task_lock = threading.Lock()

        # Timing
        self._last_claim_ts: float = 0.0

        # Log
        self.action_log: list = []

    def log(self, msg: str):
        ts    = datetime.now().strftime("%H:%M:%S")
        entry = f"[{ts}] {msg}"
        self.action_log.insert(0, entry)
        self.action_log = self.action_log[:50]
        logger.info(msg)

    # ── Task helpers ─────────────────────────────────────────────────────────

    def push_task(self, task: dict):
        with self._task_lock:
            self._task_queue.append(task)
        self.log(f"📋 Queued: {task['action']} {task.get('item_id','')} ×{task.get('amount',0)}")

    def consume_task(self):
        with self._task_lock:
            if self._current_task["action"] == "IDLE" and self._task_queue:
                self._current_task = self._task_queue.pop(0)
                self.log(f"▶️  → Mod: {self._current_task['action']} {self._current_task.get('item_id','')}")
            return self._current_task

    def mark_task_done(self):
        with self._task_lock:
            done = self._current_task
            self.log(f"✅ Done: {done['action']} {done.get('item_id','')}")
            self._current_task = _idle_task()
            # Auto-queue SELL after BUY
            if done["action"] == "BUY" and done.get("item_id"):
                sell_task = {
                    "action":      "SELL",
                    "item_id":     done["item_id"],
                    "amount":      done["amount"],
                    "price":       done.get("sell_price", round(done["price"] * 1.02, 1)),
                    "slot_target": -1,
                }
                self._task_queue.insert(0, sell_task)
                self.log(f"🔁 Auto-SELL queued: {sell_task['item_id']} @ {sell_task['price']}")
        save_state(self)

    def mark_task_failed(self, reason: str = ""):
        with self._task_lock:
            failed = self._current_task
            self.log(f"❌ Failed ({reason}): {failed['action']} {failed.get('item_id','')}")
            self._current_task = _idle_task()


bot = BotState()
load_state(bot)   # FIX 1.3 — načti stav při startu

model        = PPO.load(MODEL_PATH, device=device) if os.path.exists(MODEL_PATH) else None
price_windows: dict = {}


# ==============================================================================
#  PŮVODNÍ /predict (zachováno)
# ==============================================================================

class ItemRequest(BaseModel):
    item_id: str

@app.post("/predict")
async def predict_action(req: ItemRequest):
    if not bot.is_running:
        return {"action": 0}
    item_id = req.item_id.upper()
    if item_id in bot.active_orders:
        if bot.active_orders[item_id]["status"] != "TOP #1":
            return {"action": 1}
    if len(bot.active_orders) < 5:
        for best in bot.market_cache:
            if best["id"] == item_id:
                return {"action": 1}
    return {"action": 0}


# ==============================================================================
#  JAVA MÓD API
# ==============================================================================

@app.get("/api/task")
async def get_task():
    if not bot.is_running:
        return _idle_task()
    task = bot.consume_task()
    return {k: v for k, v in task.items() if k != "sell_price"}


# FIX 1.1 — odstraněn raw Request hack, čistý Pydantic model funguje správně
# FIX 2.1 — enqueue POUZE v Bazaar GUI, ne při "none"
# FIX 2.3 — mc_gui nyní obsahuje GuiType enum string (z opraveného TaskManager.java)

class StatusPayload(BaseModel):
    current_gui:     str
    player_coins:    float
    inventory_items: List[str]

@app.post("/api/status")
async def post_status(payload: StatusPayload):
    bot.mc_gui       = payload.current_gui
    bot.mc_coins     = payload.player_coins
    bot.mc_inventory = payload.inventory_items
    bot.last_status_ts = time.time()

    # FIX 2.1: pouze Bazaar GUI varianty spouští enqueue, ne "none" / "other"
    bazaar_guis = {"bazaar_main", "bazaar_item_list", "bazaar_orders"}
    if bot.is_running and bot.mc_gui.lower() in bazaar_guis:
        _maybe_enqueue_from_status()

    return {"ok": True}


@app.post("/api/task/done")
async def task_done():
    bot.mark_task_done()
    return {"ok": True}


class FailPayload(BaseModel):
    reason: Optional[str] = ""

@app.post("/api/task/failed")
async def task_failed(payload: FailPayload):
    bot.mark_task_failed(payload.reason or "")
    return {"ok": True}


@app.post("/api/claim")
async def force_claim():
    bot.push_task({"action": "CLAIM_ORDERS", "item_id": "", "amount": 0, "price": 0.0, "slot_target": -1})
    return RedirectResponse(url="/dashboard", status_code=303)


@app.get("/api/cancel/{item_id}")
async def force_cancel(item_id: str):
    bot.push_task({"action": "CANCEL_ORDER", "item_id": item_id.upper(), "amount": 0, "price": 0.0, "slot_target": -1})
    return RedirectResponse(url="/dashboard", status_code=303)


@app.get("/api/state")
async def api_state():
    with bot._task_lock:
        queue_snapshot = list(bot._task_queue)
        current        = dict(bot._current_task)
    age = round(time.time() - bot.last_status_ts, 1) if bot.last_status_ts > 0 else -1
    return {
        "running":          bot.is_running,
        "mode":             bot.mode,
        "cash":             bot.cash,
        "total_profit":     bot.total_profit,
        "active_orders":    bot.active_orders,
        "market_top5":      bot.market_cache[:5],
        "mc_gui":           bot.mc_gui,
        "mc_coins":         bot.mc_coins,
        "mc_inventory":     bot.mc_inventory,
        "last_status_age_s": age,
        "current_task":     current,
        "task_queue":       queue_snapshot,
    }


# ==============================================================================
#  SMART TASK ENQUEUE
# ==============================================================================

def _maybe_enqueue_from_status():
    with bot._task_lock:
        queue_len    = len(bot._task_queue)
        current_idle = bot._current_task["action"] == "IDLE"
    if queue_len > 0 or not current_idle:
        return

    # SELL pokud máme v inventáři věci k prodeji
    for item_id in bot.mc_inventory:
        if item_id in bot.active_orders:
            order = bot.active_orders[item_id]
            if order["type"] == "SELL" and order.get("status") != "QUEUED":
                order["status"] = "QUEUED"
                bot.push_task({
                    "action":      "SELL",
                    "item_id":     item_id,
                    "amount":      order["target"],
                    "price":       round(order["price"], 1),
                    "slot_target": -1,
                })
                return

    # BUY pokud jsou volné sloty
    if len(bot.active_orders) < 5 and bot.market_cache and bot.cash > 1000:
        for best in bot.market_cache:
            if best["id"] not in bot.active_orders:
                bot.push_task(_build_task_for_item(bot, best, "BUY"))
                return

    # CLAIM každých 5 minut
    now = time.time()
    if now - bot._last_claim_ts > 300 and bot.active_orders:
        bot._last_claim_ts = now
        bot.push_task({"action": "CLAIM_ORDERS", "item_id": "", "amount": 0, "price": 0.0, "slot_target": -1})


# ==============================================================================
#  AI LOGIKA
# ==============================================================================

def get_ai_trust(item_id, current_sell):
    if model is None or item_id not in price_windows or len(price_windows[item_id]) < 20:
        return 0.5
    history = np.array(price_windows[item_id][-20:])
    sma     = np.mean(history)
    obs     = np.zeros(35, dtype=np.float32)
    obs[:20] = (history / (sma + 1e-8)) - 1.0
    action, _ = model.predict(obs, deterministic=True)
    return 0.98 if action != 0 else 0.1


# ==============================================================================
#  UPDATE LOOP
# ==============================================================================

def update_loop():
    while True:
        try:
            r    = requests.get(BAZAAR_API, timeout=10)
            data = r.json().get("products", {})
            temp_results = []

            for item_id, info in data.items():
                qs     = info.get("quick_status", {})
                sell_p = qs.get("sellPrice", 0)
                buy_p  = qs.get("buyPrice",  0)
                if sell_p < 5:
                    continue
                if item_id not in price_windows:
                    price_windows[item_id] = [sell_p] * 30
                price_windows[item_id].append(sell_p)
                if len(price_windows[item_id]) > 60:
                    price_windows[item_id].pop(0)

                margin_real = (buy_p * (1 - TAX)) - sell_p
                velocity    = (qs.get("buyMovingWeek", 0) + qs.get("sellMovingWeek", 0)) / 336
                trust       = get_ai_trust(item_id, sell_p)

                if bot.mode == "FOCUS"   and item_id != bot.focus_item:          continue
                if bot.mode == "COMFORT" and (trust < 0.9 or velocity < 500):    continue
                if bot.mode == "BALANCED" and (trust < 0.7 or velocity < 100):   continue

                score = margin_real * velocity * trust
                if score > 1000 or item_id == bot.focus_item:
                    temp_results.append({
                        "id":       item_id,
                        "profit_h": int(margin_real * velocity),
                        "trust":    round(trust, 2),
                        "score":    int(score),
                        "buy_p":    sell_p + 0.1,
                        "sell_p":   buy_p  - 0.1,
                        "vel":      velocity,
                    })

            bot.market_cache = sorted(temp_results, key=lambda x: x["score"], reverse=True)[:15]

            if bot.is_running:
                changed = False
                for iid, o in list(bot.active_orders.items()):
                    if random.random() < min(0.1, o.get("vel", 0) / 10000):
                        o["status"] = "OUTBIDDED"
                    else:
                        o["status"]  = "TOP #1"
                        fill_rate    = (o.get("vel", 100) * 0.5) / 360
                        o["current"] += max(1, int(fill_rate))

                    if o["current"] >= o["target"]:
                        if o["type"] == "BUY":
                            o["type"]    = "SELL"
                            o["current"] = 0
                            changed      = True
                        else:
                            profit     = (o["price"] * o["target"] * (1 - TAX)) - (o["buy_price"] * o["target"])
                            bot.cash  += (o["price"] * o["target"] * (1 - TAX))
                            bot.total_profit += profit
                            bot.log(f"💰 Closed {iid}: +{profit:,.0f} coins")
                            del bot.active_orders[iid]
                            changed = True

                if len(bot.active_orders) < 5 and bot.market_cache:
                    for best in bot.market_cache:
                        if best["id"] not in bot.active_orders:
                            qty = int((bot.cash * 0.2) / best["buy_p"])
                            if qty > 0:
                                bot.cash -= qty * best["buy_p"]
                                bot.active_orders[best["id"]] = {
                                    "type":      "BUY",
                                    "current":   0,
                                    "target":    qty,
                                    "status":    "TOP #1",
                                    "price":     best["buy_p"],
                                    "buy_price": best["buy_p"],
                                    "vel":       best["vel"],
                                }
                                changed = True
                                if bot.mc_gui in ("bazaar_main", "bazaar_item_list"):
                                    task = _build_task_for_item(bot, best, "BUY")
                                    with bot._task_lock:
                                        if not bot._task_queue and bot._current_task["action"] == "IDLE":
                                            bot._task_queue.append(task)
                                break

                if changed:
                    save_state(bot)   # FIX 1.3 — uložit po každé změně

        except Exception as e:
            logger.error(f"Update loop error: {e}")

        time.sleep(10)


# ==============================================================================
#  DASHBOARD
# ==============================================================================

@app.get("/dashboard", response_class=HTMLResponse)
async def get_dashboard():
    order_cards = ""
    for iid, o in bot.active_orders.items():
        progress   = (o["current"] / max(o["target"], 1)) * 100
        status_clr = "#10b981" if o["status"] == "TOP #1" else "#f43f5e"
        type_clr   = "#3b82f6" if o["type"]   == "BUY"   else "#f59e0b"
        order_cards += f"""
        <div class="order-card">
            <div class="order-header">
                <span class="order-id">{iid}</span>
                <span class="order-type" style="background:{type_clr}33;color:{type_clr}">{o['type']}</span>
            </div>
            <div class="order-status">
                <span class="dot" style="background:{status_clr}"></span> {o['status']}
            </div>
            <div class="progress-container"><div class="progress-bar" style="width:{progress:.1f}%"></div></div>
            <div class="order-footer">
                <span>{o['current']} / {o['target']} units</span>
                <span>{int(progress)}%</span>
            </div>
            <div style="margin-top:8px">
                <a href="/api/cancel/{iid}" style="font-size:0.7rem;color:#f43f5e;text-decoration:none">⊗ Cancel in-game</a>
            </div>
        </div>"""

    with bot._task_lock:
        current_task  = dict(bot._current_task)
        queued_tasks  = list(bot._task_queue)

    task_rows = "".join([
        f"<tr class='border-t border-slate-800'>"
        f"<td class='py-1 text-blue-400'>{t['action']}</td>"
        f"<td class='py-1 text-xs'>{t.get('item_id','—')}</td>"
        f"<td class='py-1 text-right'>{t.get('amount',0)}</td>"
        f"<td class='py-1 text-right text-emerald-400'>{t.get('price',0):.1f}</td></tr>"
        for t in queued_tasks[:8]
    ])

    age       = round(time.time() - bot.last_status_ts, 1) if bot.last_status_ts > 0 else 9999
    mod_ok    = age < 15
    mod_dot   = "#10b981" if mod_ok else "#f43f5e"
    mod_label = f"Connected ({int(age)}s ago)" if mod_ok else "No signal"
    inv_prev  = ", ".join(bot.mc_inventory[:5]) + ("…" if len(bot.mc_inventory) > 5 else "") or "—"
    log_html  = "".join([f"<div class='text-xs text-slate-400 py-0.5 border-b border-slate-800'>{e}</div>" for e in bot.action_log[:15]])
    if not log_html:
        log_html = "<div class='text-xs text-slate-500 italic py-4 text-center'>No activity yet.</div>"

    return f"""<!DOCTYPE html>
<html lang="cs">
<head>
    <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Bazaar Overlord</title>
    <meta http-equiv="refresh" content="5">
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600;700&display=swap" rel="stylesheet">
    <style>
        body {{ background:#0f172a; font-family:'Inter',sans-serif; color:#f8fafc; }}
        .glass {{ background:rgba(30,41,59,0.7); backdrop-filter:blur(10px); border:1px solid rgba(255,255,255,0.1); border-radius:16px; }}
        .order-card {{ background:rgba(51,65,85,0.4); border:1px solid rgba(255,255,255,0.05); padding:15px; border-radius:12px; margin-bottom:12px; }}
        .order-header {{ display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; }}
        .order-id {{ font-weight:700; color:#60a5fa; font-size:0.9rem; }}
        .order-type {{ font-size:0.7rem; font-weight:800; padding:2px 8px; border-radius:4px; text-transform:uppercase; }}
        .progress-container {{ background:#1e293b; border-radius:10px; height:6px; margin:10px 0; overflow:hidden; }}
        .progress-bar {{ background:#10b981; height:100%; transition:width 0.5s ease; }}
        .order-footer {{ display:flex; justify-content:space-between; font-size:0.75rem; color:#94a3b8; }}
        .dot {{ height:8px; width:8px; border-radius:50%; display:inline-block; margin-right:5px; }}
        .sp {{ animation:pulse 2s infinite; }}
        @keyframes pulse {{ 0%{{opacity:1}} 50%{{opacity:0.4}} 100%{{opacity:1}} }}
        input,select {{ background:#1e293b !important; border:1px solid #334155 !important; color:white !important; border-radius:8px !important; }}
    </style>
</head>
<body class="p-4 md:p-8">
<div class="max-w-7xl mx-auto">

    <div class="flex justify-between items-center mb-8 flex-wrap gap-4">
        <div>
            <h1 class="text-3xl font-bold">🦅 Bazaar Overlord <span class="text-slate-500 font-light">v10</span></h1>
            <div class="flex items-center mt-1 gap-4 flex-wrap">
                <span><span class="dot sp" style="background:{'#10b981' if bot.is_running else '#f43f5e'}"></span>
                <span class="text-sm text-slate-400">{"Active" if bot.is_running else "Standby"} — {bot.mode}</span></span>
                <span><span class="dot" style="background:{mod_dot}"></span>
                <span class="text-sm text-slate-400">Mod: {mod_label}</span></span>
            </div>
        </div>
        <div class="flex gap-3 flex-wrap">
            <a href="/toggle" class="px-5 py-2 rounded-xl font-bold {'bg-rose-500 hover:bg-rose-600' if bot.is_running else 'bg-emerald-500 hover:bg-emerald-600'}">
                {"STOP" if bot.is_running else "START"}
            </a>
            <a href="/api/claim" class="px-5 py-2 rounded-xl font-bold bg-amber-600 hover:bg-amber-700">CLAIM ORDERS</a>
            <a href="/panic" class="px-5 py-2 rounded-xl font-bold bg-slate-700 hover:bg-rose-700 border border-rose-500/30">PANIC</a>
        </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-4 gap-6">

        <!-- Sloupec 1: Stats + Config -->
        <div class="space-y-6">
            <div class="glass p-6">
                <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">Portfolio</h3>
                <div class="space-y-3">
                    <div>
                        <span class="text-slate-400 text-xs">Net Worth</span>
                        <div class="text-2xl font-bold">{(bot.cash + bot.total_profit):,.0f} <span class="text-sm text-slate-500">coins</span></div>
                    </div>
                    <div class="flex justify-between py-2 border-t border-slate-700">
                        <span class="text-slate-400 text-sm">Profit</span>
                        <span class="text-emerald-400 font-bold">+{bot.total_profit:,.0f}</span>
                    </div>
                    <div class="flex justify-between py-2 border-t border-slate-700">
                        <span class="text-slate-400 text-sm">Cash</span>
                        <span class="text-white font-semibold">{bot.cash:,.0f}</span>
                    </div>
                    <div class="flex justify-between py-2 border-t border-slate-700">
                        <span class="text-slate-400 text-sm">MC Coins</span>
                        <span class="text-yellow-400">{bot.mc_coins:,.0f}</span>
                    </div>
                </div>
            </div>

            <div class="glass p-6">
                <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">Configuration</h3>
                <form action="/update_settings" method="post" class="space-y-4">
                    <div>
                        <label class="block text-xs text-slate-500 mb-1">Strategy</label>
                        <select name="mode" class="w-full p-2 text-sm">
                            <option value="AGGRESSIVE" {"selected" if bot.mode=="AGGRESSIVE" else ""}>Aggressive</option>
                            <option value="BALANCED"   {"selected" if bot.mode=="BALANCED"   else ""}>Balanced</option>
                            <option value="COMFORT"    {"selected" if bot.mode=="COMFORT"    else ""}>Comfort</option>
                            <option value="FOCUS"      {"selected" if bot.mode=="FOCUS"      else ""}>Focus</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-xs text-slate-500 mb-1">Focus Item</label>
                        <input type="text" name="focus_id" value="{bot.focus_item}" class="w-full p-2 text-sm" placeholder="IRON_INGOT">
                    </div>
                    <div>
                        <label class="block text-xs text-slate-500 mb-1">Budget</label>
                        <input type="number" name="budget" value="{int(bot.cash)}" class="w-full p-2 text-sm">
                    </div>
                    <button type="submit" class="w-full py-2 bg-blue-600 hover:bg-blue-700 rounded-lg font-semibold text-sm">Apply</button>
                </form>
            </div>
        </div>

        <!-- Sloupec 2-3: Orders + Market -->
        <div class="lg:col-span-2 space-y-6">
            <div class="glass p-6">
                <div class="flex justify-between items-center mb-5">
                    <h3 class="text-slate-400 text-sm font-semibold uppercase">Live Orders</h3>
                    <span class="text-xs text-slate-500">{len(bot.active_orders)}/5</span>
                </div>
                <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {order_cards if bot.active_orders else '<div class="col-span-2 py-16 text-center text-slate-500 italic">No active orders.</div>'}
                </div>
            </div>
            <div class="glass p-6">
                <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">Top Opportunities</h3>
                <div class="overflow-x-auto">
                    <table class="w-full text-sm">
                        <thead class="text-slate-500"><tr>
                            <th class="pb-3 text-left">Product</th>
                            <th class="pb-3 text-right">Profit/hr</th>
                            <th class="pb-3 text-right">Trust</th>
                            <th class="pb-3 text-right">Score</th>
                        </tr></thead>
                        <tbody class="text-slate-300">
                            {"".join([f'<tr class="border-t border-slate-800 hover:bg-slate-800/30"><td class="py-2 text-blue-400">{i["id"]}</td><td class="text-right text-emerald-400">{i["profit_h"]:,}</td><td class="text-right">{i["trust"]}</td><td class="text-right font-bold">{i["score"]:,}</td></tr>' for i in bot.market_cache[:8]])}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>

        <!-- Sloupec 4: Mod status + Task queue + Log -->
        <div class="space-y-6">
            <div class="glass p-6">
                <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">🎮 Minecraft Mod</h3>
                <div class="space-y-2 text-sm">
                    <div class="flex justify-between">
                        <span class="text-slate-400">GUI</span>
                        <span class="text-white font-mono text-xs">{bot.mc_gui or '—'}</span>
                    </div>
                    <div class="flex justify-between border-t border-slate-700 pt-2">
                        <span class="text-slate-400">Coins</span>
                        <span class="text-yellow-400">{bot.mc_coins:,.0f}</span>
                    </div>
                    <div class="border-t border-slate-700 pt-2">
                        <span class="text-slate-400 block mb-1 text-xs">Inventory</span>
                        <span class="text-xs text-slate-300 font-mono">{inv_prev}</span>
                    </div>
                </div>
            </div>

            <div class="glass p-6">
                <h3 class="text-slate-400 text-sm font-semibold uppercase mb-3">📋 Task Queue</h3>
                <div class="mb-3 p-3 rounded-lg" style="background:rgba(16,185,129,0.1);border:1px solid rgba(16,185,129,0.2)">
                    <div class="text-xs text-slate-400 mb-1">Current</div>
                    <div class="font-bold text-emerald-400">{current_task['action']}</div>
                    <div class="text-xs text-slate-300">{current_task.get('item_id','') or '—'} {"× "+str(current_task['amount']) if current_task.get('amount') else ''}</div>
                </div>
                <div class="text-xs text-slate-500 mb-2">Queued ({len(queued_tasks)})</div>
                <table class="w-full text-xs">
                    <thead class="text-slate-500"><tr>
                        <th class="text-left pb-1">Action</th><th class="text-left pb-1">Item</th>
                        <th class="text-right pb-1">Qty</th><th class="text-right pb-1">Price</th>
                    </tr></thead>
                    <tbody>{task_rows or '<tr><td colspan="4" class="py-3 text-slate-600 italic text-center">Empty</td></tr>'}</tbody>
                </table>
            </div>

            <div class="glass p-6">
                <h3 class="text-slate-400 text-sm font-semibold uppercase mb-3">📜 Activity Log</h3>
                <div style="max-height:260px;overflow-y:auto">{log_html}</div>
            </div>
        </div>

    </div>
</div>
</body>
</html>"""


# ==============================================================================
#  AKCE
# ==============================================================================

# FIX 1.2 — budget jako str, manuální konverze na float
@app.post("/update_settings")
async def update_settings(
    mode:     str = Form(...),
    focus_id: str = Form(...),
    budget:   str = Form(...),    # <-- oprava: str místo float
):
    bot.mode       = mode
    bot.focus_item = focus_id.upper()
    if not bot.is_running:
        try:
            bot.cash = float(budget)
        except ValueError:
            pass
    save_state(bot)
    return RedirectResponse(url="/dashboard", status_code=303)


@app.get("/toggle")
async def toggle():
    bot.is_running = not bot.is_running
    bot.log(f"System {'▶️  started' if bot.is_running else '⏹️  stopped'}")
    save_state(bot)
    return RedirectResponse(url="/dashboard", status_code=303)


@app.get("/panic")
async def panic():
    bot.is_running = False
    bot.active_orders = {}
    with bot._task_lock:
        bot._task_queue.clear()
        bot._current_task = _idle_task()
    bot.log("🚨 PANIC — vše smazáno")
    save_state(bot)
    return RedirectResponse(url="/dashboard", status_code=303)


# ==============================================================================
#  START
# ==============================================================================

threading.Thread(target=update_loop, daemon=True).start()

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
