import requests, time, threading, os, uvicorn, random
import numpy as np
import torch
from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel
from stable_baselines3 import PPO
from typing import List, Optional
from datetime import datetime
import logging

# --- KONFIGURACE ---
BAZAAR_API = "https://api.hypixel.net/v2/skyblock/bazaar"
MODEL_PATH = "bazaar_scalper_brain_v3.zip"
TAX = 0.0125

# Optimalizace pro Apple Silicon (M1/M2/M3)
device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")

app = FastAPI()
logger = logging.getLogger("BazaarOverlord")

# ==============================================================================
#  BOT STATE
# ==============================================================================

def _idle_task() -> dict:
    return {"action": "IDLE", "item_id": "", "amount": 0, "price": 0.0, "slot_target": -1}


class BotState:
    def __init__(self):
        self.is_running = False
        self.mode = "BALANCED"
        self.focus_item = ""
        self.cash = 10_000_000.0
        self.initial_cash = 10_000_000.0
        self.total_profit = 0
        self.active_orders = {}
        self.market_cache = []

        # ── Stav přijatý z Java módu přes POST /api/status ──────────────────
        self.mc_gui: str = "none"          # titulek aktuálně otevřeného GUI
        self.mc_coins: float = 0.0         # koiny hráče ze scoreboardu
        self.mc_inventory: List[str] = []  # item ID v inventáři hráče
        self.last_status_ts: float = 0.0   # čas poslední zprávy od módu

        # ── Task systém pro Java mód ─────────────────────────────────────────
        # Fronta úkolů. Každý úkol je dict s klíči dle /api/task kontraktu.
        self._task_queue: list = []
        self._current_task: dict = _idle_task()
        self._task_lock = threading.Lock()

        # ── Log akcí (krátká history pro dashboard) ──────────────────────────
        self.action_log: list = []         # max 50 zápisů

    def log(self, msg: str):
        ts = datetime.now().strftime("%H:%M:%S")
        entry = f"[{ts}] {msg}"
        self.action_log.insert(0, entry)
        self.action_log = self.action_log[:50]
        logger.info(msg)

    # ── Task helpers ─────────────────────────────────────────────────────────

    def push_task(self, task: dict):
        """Přidá úkol do fronty (volá update_loop)."""
        with self._task_lock:
            self._task_queue.append(task)
            self.log(f"📋 Queued task: {task['action']} {task.get('item_id','')} x{task.get('amount',0)}")

    def consume_task(self):
        """
        Vrátí aktuální úkol pro Java mód.
        Pokud je dokončen (nebo je IDLE), načte další z fronty.
        """
        with self._task_lock:
            if self._current_task["action"] == "IDLE" and self._task_queue:
                self._current_task = self._task_queue.pop(0)
                self.log(f"▶️  Sending task to mod: {self._current_task}")
            return self._current_task

    def mark_task_done(self):
        """Java mód zavolá, když úkol dokončil."""
        with self._task_lock:
            done = self._current_task
            self.log(f"✅ Task done: {done['action']} {done.get('item_id','')}")
            self._current_task = _idle_task()
            # Pokud šlo o BUY — přidej odpovídající SELL task
            if done["action"] == "BUY":
                sell_task = {
                    "action": "SELL",
                    "item_id": done["item_id"],
                    "amount": done["amount"],
                    "price": done.get("sell_price", done["price"] * 1.02),
                    "slot_target": -1,
                }
                self._task_queue.insert(0, sell_task)
                self.log(f"🔁 Auto-queued SELL after BUY: {sell_task}")

    def mark_task_failed(self, reason: str = ""):
        """Java mód zavolá, když selhal."""
        with self._task_lock:
            failed = self._current_task
            self.log(f"❌ Task failed ({reason}): {failed['action']} {failed.get('item_id','')}")
            self._current_task = _idle_task()


bot = BotState()

# Načtení modelu
model = PPO.load(MODEL_PATH, device=device) if os.path.exists(MODEL_PATH) else None
price_windows = {}


# ==============================================================================
#  HELPER
# ==============================================================================

def _idle_task() -> dict:
    return {"action": "IDLE", "item_id": "", "amount": 0, "price": 0.0, "slot_target": -1}


def _build_task_for_item(best: dict, action: str = "BUY") -> dict:
    """Sestaví task dict z market_cache záznamu."""
    price = best["buy_p"] if action == "BUY" else best["sell_p"]
    qty = max(1, int((bot.cash * 0.18) / max(price, 0.01)))
    return {
        "action": action,
        "item_id": best["id"],
        "amount": qty,
        "price": round(price, 1),
        "sell_price": round(best["sell_p"], 1),   # uchováme pro auto-SELL
        "slot_target": -1,
    }


# ==============================================================================
#  PŮVODNÍ /predict ENDPOINT (zachováno beze změny)
# ==============================================================================

class ItemRequest(BaseModel):
    item_id: str

@app.post("/predict")
async def predict_action(req: ItemRequest):
    """
    Původní endpoint – Java mód posílá item_id, server odpovídá 1/0.
    Zachováno pro zpětnou kompatibilitu.
    """
    if not bot.is_running:
        return {"action": 0}

    item_id = req.item_id.upper()

    if item_id in bot.active_orders:
        order = bot.active_orders[item_id]
        if order["status"] != "TOP #1":
            return {"action": 1}

    if len(bot.active_orders) < 5:
        for best in bot.market_cache:
            if best["id"] == item_id:
                return {"action": 1}

    return {"action": 0}


# ==============================================================================
#  NOVÉ ENDPOINTY PRO JAVA MÓD
# ==============================================================================

# ── GET /api/task ─────────────────────────────────────────────────────────────

@app.get("/api/task")
async def get_task():
    """
    Java mód sem polluje každé 1–2 s.
    Vrací aktuální úkol nebo IDLE, pokud není co dělat.
    """
    if not bot.is_running:
        return _idle_task()

    task = bot.consume_task()
    # Odstraníme sell_price — Java to nepotřebuje
    return {k: v for k, v in task.items() if k != "sell_price"}


# ── POST /api/status ──────────────────────────────────────────────────────────

class StatusPayload(BaseModel):
    current_gui: str
    player_coins: float
    inventory_items: List[str]

@app.post("/api/status")
async def post_status(request: Request):
    raw = await request.json()
    print("RAW STATUS:", raw)
    payload = StatusPayload(**raw)
    bot.mc_gui = payload.current_gui
    bot.mc_coins = payload.player_coins
    bot.mc_inventory = payload.inventory_items
    bot.last_status_ts = time.time()
    if bot.is_running and bot.mc_gui.lower() in ("bazaar", "none"):
        _maybe_enqueue_from_status()
    return {"ok": True}


# ── POST /api/task/done ────────────────────────────────────────────────────────

@app.post("/api/task/done")
async def task_done():
    """Java mód zavolá po úspěšném dokončení úkolu."""
    bot.mark_task_done()
    return {"ok": True}


# ── POST /api/task/failed ─────────────────────────────────────────────────────

class FailPayload(BaseModel):
    reason: Optional[str] = ""

@app.post("/api/task/failed")
async def task_failed(payload: FailPayload):
    """Java mód zavolá, když úkol selhal."""
    bot.mark_task_failed(payload.reason)
    return {"ok": True}


# ── POST /api/claim ───────────────────────────────────────────────────────────

@app.post("/api/claim")
async def force_claim():
    """
    Dashboard tlačítko — vynutí CLAIM_ORDERS úkol bez čekání na auto-logiku.
    """
    bot.push_task({"action": "CLAIM_ORDERS", "item_id": "", "amount": 0, "price": 0.0, "slot_target": -1})
    return RedirectResponse(url="/dashboard", status_code=303)


# ── GET /api/cancel/{item_id} ─────────────────────────────────────────────────

@app.get("/api/cancel/{item_id}")
async def force_cancel(item_id: str):
    """Dashboard tlačítko — vynutí zrušení konkrétní objednávky v Bazaaru."""
    bot.push_task({"action": "CANCEL_ORDER", "item_id": item_id.upper(), "amount": 0, "price": 0.0, "slot_target": -1})
    return RedirectResponse(url="/dashboard", status_code=303)


# ── GET /api/state (debug) ────────────────────────────────────────────────────

@app.get("/api/state")
async def api_state():
    """Vrátí kompletní stav serveru jako JSON — užitečné pro debugging."""
    with bot._task_lock:
        queue_snapshot = list(bot._task_queue)
        current = dict(bot._current_task)
    return {
        "running": bot.is_running,
        "mode": bot.mode,
        "cash": bot.cash,
        "total_profit": bot.total_profit,
        "active_orders": bot.active_orders,
        "market_top5": bot.market_cache[:5],
        "mc_gui": bot.mc_gui,
        "mc_coins": bot.mc_coins,
        "mc_inventory": bot.mc_inventory,
        "last_status_age_s": round(time.time() - bot.last_status_ts, 1),
        "current_task": current,
        "task_queue": queue_snapshot,
    }


# ==============================================================================
#  SMART TASK ENQUEUE — bridguje starou logiku s novým task systémem
# ==============================================================================

def _maybe_enqueue_from_status():
    """
    Rozhodne, zda přidat BUY nebo CLAIM_ORDERS úkol na základě
    aktuálního stavu hráče (inventář, GUI, coins).
    Volá se po každé POST /api/status.
    """
    with bot._task_lock:
        queue_len = len(bot._task_queue)
        current_idle = bot._current_task["action"] == "IDLE"

    # Nepřidávej, pokud už ve frontě něco čeká
    if queue_len > 0 or not current_idle:
        return

    # Hráč má v inventáři položky, které patří do aktivních objednávek → SELL
    for item_id in bot.mc_inventory:
        if item_id in bot.active_orders:
            order = bot.active_orders[item_id]
            if order["type"] == "SELL" and order["status"] != "QUEUED":
                order["status"] = "QUEUED"
                bot.push_task({
                    "action": "SELL",
                    "item_id": item_id,
                    "amount": order["target"],
                    "price": round(order["price"], 1),
                    "slot_target": -1,
                })
                return

    # Máme volné order sloty → BUY nejlepší příležitost
    if len(bot.active_orders) < 5 and bot.market_cache and bot.cash > 1000:
        for best in bot.market_cache:
            if best["id"] not in bot.active_orders:
                task = _build_task_for_item(best, "BUY")
                bot.push_task(task)
                return

    # Pokud od posledního claimu uplynulo víc než 5 minut → CLAIM
    claim_interval = 300
    now = time.time()
    if not hasattr(bot, "_last_claim_ts"):
        bot._last_claim_ts = 0.0
    if now - bot._last_claim_ts > claim_interval and bot.active_orders:
        bot._last_claim_ts = now
        bot.push_task({"action": "CLAIM_ORDERS", "item_id": "", "amount": 0, "price": 0.0, "slot_target": -1})


# ==============================================================================
#  AI LOGIKA (nezměněno)
# ==============================================================================

def get_ai_trust(item_id, current_sell):
    if model is None or item_id not in price_windows or len(price_windows[item_id]) < 20:
        return 0.5
    history = np.array(price_windows[item_id][-20:])
    sma = np.mean(history)
    obs = np.zeros(35, dtype=np.float32)
    obs[:20] = (history / (sma + 1e-8)) - 1.0
    action, _ = model.predict(obs, deterministic=True)
    return 0.98 if action != 0 else 0.1


# ==============================================================================
#  UPDATE LOOP (rozšířeno o task-driven logiku)
# ==============================================================================

def update_loop():
    while True:
        try:
            r = requests.get(BAZAAR_API, timeout=10)
            data = r.json().get("products", {})
            temp_results = []

            for item_id, info in data.items():
                qs = info.get("quick_status", {})
                sell_p = qs.get("sellPrice", 0)
                buy_p  = qs.get("buyPrice", 0)
                if sell_p < 5:
                    continue
                if item_id not in price_windows:
                    price_windows[item_id] = [sell_p] * 30
                price_windows[item_id].append(sell_p)
                if len(price_windows[item_id]) > 60:
                    price_windows[item_id].pop(0)

                margin_real = (buy_p * (1 - TAX)) - sell_p
                velocity = (qs.get("buyMovingWeek", 0) + qs.get("sellMovingWeek", 0)) / 336
                trust = get_ai_trust(item_id, sell_p)

                if bot.mode == "FOCUS"     and item_id != bot.focus_item: continue
                if bot.mode == "COMFORT"   and (trust < 0.9 or velocity < 500): continue
                if bot.mode == "BALANCED"  and (trust < 0.7 or velocity < 100): continue

                score = margin_real * velocity * trust
                if score > 1000 or item_id == bot.focus_item:
                    temp_results.append({
                        "id": item_id,
                        "profit_h": int(margin_real * velocity),
                        "trust": round(trust, 2),
                        "score": int(score),
                        "buy_p": sell_p + 0.1,
                        "sell_p": buy_p - 0.1,
                        "vel": velocity,
                    })

            bot.market_cache = sorted(temp_results, key=lambda x: x["score"], reverse=True)[:15]

            if bot.is_running:
                # Simulace plnění objednávek (původní logika)
                for iid, o in list(bot.active_orders.items()):
                    if random.random() < min(0.1, o.get("vel", 0) / 10000):
                        o["status"] = "OUTBIDDED"
                    else:
                        o["status"] = "TOP #1"
                        fill_rate = (o.get("vel", 100) * 0.5) / 360
                        o["current"] += max(1, int(fill_rate))

                    if o["current"] >= o["target"]:
                        if o["type"] == "BUY":
                            o["type"] = "SELL"
                            o["current"] = 0
                        else:
                            profit = (o["price"] * o["target"] * (1 - TAX)) - (o["buy_price"] * o["target"])
                            bot.cash += (o["price"] * o["target"] * (1 - TAX))
                            bot.total_profit += profit
                            bot.log(f"💰 Order closed: {iid} | profit +{profit:,.0f}")
                            del bot.active_orders[iid]

                # Otevírání nových objednávek
                if len(bot.active_orders) < 5 and bot.market_cache:
                    for best in bot.market_cache:
                        if best["id"] not in bot.active_orders:
                            qty = int((bot.cash * 0.2) / best["buy_p"])
                            if qty > 0:
                                bot.cash -= qty * best["buy_p"]
                                bot.active_orders[best["id"]] = {
                                    "type": "BUY",
                                    "current": 0,
                                    "target": qty,
                                    "status": "TOP #1",
                                    "price": best["buy_p"],
                                    "buy_price": best["buy_p"],
                                    "vel": best["vel"],
                                }
                                # Pokud hráč sedí v Bazaaru → rovnou enqueue
                                if bot.mc_gui.lower() == "bazaar":
                                    task = _build_task_for_item(best, "BUY")
                                    with bot._task_lock:
                                        if not bot._task_queue and bot._current_task["action"] == "IDLE":
                                            bot._task_queue.append(task)
                                break

        except Exception as e:
            logger.error(f"Update error: {e}")

        time.sleep(10)


# ==============================================================================
#  DASHBOARD (rozšířeno o mod status panel a action log)
# ==============================================================================

@app.get("/dashboard", response_class=HTMLResponse)
async def get_dashboard():
    # ── Order cards ───────────────────────────────────────────────────────────
    order_cards = ""
    for iid, o in bot.active_orders.items():
        progress   = (o["current"] / o["target"]) * 100
        status_clr = "#10b981" if o["status"] == "TOP #1" else "#f43f5e"
        type_clr   = "#3b82f6" if o["type"]   == "BUY"    else "#f59e0b"
        order_cards += f"""
        <div class="order-card">
            <div class="order-header">
                <span class="order-id">{iid}</span>
                <span class="order-type" style="background:{type_clr}33;color:{type_clr}">{o['type']}</span>
            </div>
            <div class="order-status">
                <span class="dot" style="background:{status_clr}"></span> {o['status']}
            </div>
            <div class="progress-container">
                <div class="progress-bar" style="width:{progress}%"></div>
            </div>
            <div class="order-footer">
                <span>{o['current']} / {o['target']} units</span>
                <span>{int(progress)}%</span>
            </div>
            <div style="margin-top:8px">
                <a href="/api/cancel/{iid}" style="font-size:0.7rem;color:#f43f5e;text-decoration:none">⊗ Cancel in-game</a>
            </div>
        </div>
        """

    # ── Task queue panel ──────────────────────────────────────────────────────
    with bot._task_lock:
        current_task   = dict(bot._current_task)
        queued_tasks   = list(bot._task_queue)

    task_rows = ""
    for t in queued_tasks[:8]:
        task_rows += f"<tr class='border-t border-slate-800'><td class='py-1 text-blue-400'>{t['action']}</td><td class='py-1'>{t.get('item_id','—')}</td><td class='py-1 text-right'>{t.get('amount',0)}</td><td class='py-1 text-right text-emerald-400'>{t.get('price',0):.1f}</td></tr>"

    # ── Mod status indicators ─────────────────────────────────────────────────
    age = time.time() - bot.last_status_ts
    mod_connected = age < 15
    mod_dot_clr   = "#10b981" if mod_connected else "#f43f5e"
    mod_label     = f"Connected ({int(age)}s ago)" if mod_connected else "No signal"
    inv_preview   = ", ".join(bot.mc_inventory[:5]) + ("…" if len(bot.mc_inventory) > 5 else "") or "—"

    # ── Action log ────────────────────────────────────────────────────────────
    log_html = "".join([f"<div class='text-xs text-slate-400 py-0.5 border-b border-slate-800'>{e}</div>" for e in bot.action_log[:15]])
    if not log_html:
        log_html = "<div class='text-xs text-slate-500 italic py-4 text-center'>No activity yet.</div>"

    return f"""
    <!DOCTYPE html>
    <html lang="cs">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Bazaar Overlord Control</title>
        <meta http-equiv="refresh" content="5">
        <script src="https://cdn.tailwindcss.com"></script>
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600;700&display=swap" rel="stylesheet">
        <style>
            body {{ background-color: #0f172a; font-family: 'Inter', sans-serif; color: #f8fafc; }}
            .glass {{ background: rgba(30,41,59,0.7); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); border-radius: 16px; }}
            .order-card {{ background: rgba(51,65,85,0.4); border: 1px solid rgba(255,255,255,0.05); padding: 15px; border-radius: 12px; margin-bottom: 12px; }}
            .order-header {{ display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; }}
            .order-id {{ font-weight:700; color:#60a5fa; font-size:0.9rem; }}
            .order-type {{ font-size:0.7rem; font-weight:800; padding:2px 8px; border-radius:4px; text-transform:uppercase; }}
            .progress-container {{ background:#1e293b; border-radius:10px; height:6px; margin:10px 0; overflow:hidden; }}
            .progress-bar {{ background:#10b981; height:100%; transition:width 0.5s ease; }}
            .order-footer {{ display:flex; justify-content:space-between; font-size:0.75rem; color:#94a3b8; }}
            .dot {{ height:8px; width:8px; border-radius:50%; display:inline-block; margin-right:5px; }}
            .status-pulse {{ animation: pulse 2s infinite; }}
            @keyframes pulse {{ 0%{{opacity:1}} 50%{{opacity:0.4}} 100%{{opacity:1}} }}
            input, select {{ background:#1e293b !important; border:1px solid #334155 !important; color:white !important; border-radius:8px !important; }}
            .tag {{ display:inline-block; font-size:0.65rem; padding:1px 6px; border-radius:4px; font-weight:700; }}
        </style>
    </head>
    <body class="p-4 md:p-10">
    <div class="max-w-7xl mx-auto">

        <!-- Header -->
        <div class="flex justify-between items-center mb-8 flex-wrap gap-4">
            <div>
                <h1 class="text-3xl font-bold tracking-tight">🦅 Bazaar Overlord <span class="text-slate-500 font-light">v10</span></h1>
                <div class="flex items-center mt-1 gap-4">
                    <span>
                        <span class="dot status-pulse" style="background:{'#10b981' if bot.is_running else '#f43f5e'}"></span>
                        <span class="text-sm font-medium text-slate-400">System {"Active" if bot.is_running else "Standby"} — Mode: {bot.mode}</span>
                    </span>
                    <span>
                        <span class="dot" style="background:{mod_dot_clr}"></span>
                        <span class="text-sm text-slate-400">Minecraft mod: {mod_label}</span>
                    </span>
                </div>
            </div>
            <div class="flex gap-3 flex-wrap">
                <a href="/toggle" class="px-6 py-2 rounded-xl font-bold transition-all {'bg-rose-500 hover:bg-rose-600' if bot.is_running else 'bg-emerald-500 hover:bg-emerald-600'}">
                    {"STOP" if bot.is_running else "START"}
                </a>
                <a href="/api/claim" class="px-6 py-2 rounded-xl font-bold bg-amber-600 hover:bg-amber-700 transition-all">CLAIM ORDERS</a>
                <a href="/panic" class="px-6 py-2 rounded-xl font-bold bg-slate-700 hover:bg-rose-700 transition-all border border-rose-500/30">PANIC</a>
            </div>
        </div>

        <div class="grid grid-cols-1 lg:grid-cols-4 gap-6">

            <!-- Col 1: Stats + Config -->
            <div class="space-y-6">
                <div class="glass p-6">
                    <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">Portfolio Overview</h3>
                    <div class="space-y-4">
                        <div>
                            <span class="text-slate-400 text-xs">Total Net Worth</span>
                            <div class="text-2xl font-bold text-white">{(bot.cash + bot.total_profit):,.0f} <span class="text-sm text-slate-500">coins</span></div>
                        </div>
                        <div class="flex justify-between py-3 border-t border-slate-700">
                            <span class="text-slate-400 text-sm">Real Profit</span>
                            <span class="text-emerald-400 font-bold">+{bot.total_profit:,.0f}</span>
                        </div>
                        <div class="flex justify-between py-3 border-t border-slate-700">
                            <span class="text-slate-400 text-sm">Available Cash</span>
                            <span class="text-white font-semibold">{bot.cash:,.0f}</span>
                        </div>
                        <div class="flex justify-between py-3 border-t border-slate-700">
                            <span class="text-slate-400 text-sm">MC Coins</span>
                            <span class="text-yellow-400 font-semibold">{bot.mc_coins:,.0f}</span>
                        </div>
                    </div>
                </div>

                <div class="glass p-6">
                    <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">Configuration</h3>
                    <form action="/update_settings" method="post" class="space-y-4">
                        <div>
                            <label class="block text-xs text-slate-500 mb-1">Trading Strategy</label>
                            <select name="mode" class="w-full p-2 text-sm">
                                <option value="AGGRESSIVE" {"selected" if bot.mode=="AGGRESSIVE" else ""}>Aggressive</option>
                                <option value="BALANCED"   {"selected" if bot.mode=="BALANCED"   else ""}>Balanced</option>
                                <option value="COMFORT"    {"selected" if bot.mode=="COMFORT"    else ""}>Comfort</option>
                                <option value="FOCUS"      {"selected" if bot.mode=="FOCUS"      else ""}>Focus</option>
                            </select>
                        </div>
                        <div>
                            <label class="block text-xs text-slate-500 mb-1">Focus Item ID</label>
                            <input type="text" name="focus_id" value="{bot.focus_item}" class="w-full p-2 text-sm" placeholder="e.g. IRON_INGOT">
                        </div>
                        <div>
                            <label class="block text-xs text-slate-500 mb-1">Budget</label>
                            <input type="number" name="budget" value="{int(bot.cash)}" class="w-full p-2 text-sm">
                        </div>
                        <button type="submit" class="w-full py-2 bg-blue-600 hover:bg-blue-700 rounded-lg font-semibold text-sm">Apply</button>
                    </form>
                </div>
            </div>

            <!-- Col 2-3: Orders + Opportunities -->
            <div class="lg:col-span-2 space-y-6">
                <div class="glass p-6">
                    <div class="flex justify-between items-center mb-6">
                        <h3 class="text-slate-400 text-sm font-semibold uppercase">Live Orders</h3>
                        <span class="text-xs text-slate-500">{len(bot.active_orders)}/5 slots</span>
                    </div>
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        {order_cards if bot.active_orders else '<div class="col-span-2 py-16 text-center text-slate-500 italic">No active orders.</div>'}
                    </div>
                </div>

                <div class="glass p-6">
                    <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">Top Market Opportunities</h3>
                    <div class="overflow-x-auto">
                        <table class="w-full text-sm">
                            <thead class="text-slate-500">
                                <tr>
                                    <th class="pb-3 text-left">Product</th>
                                    <th class="pb-3 text-right">Profit/hr</th>
                                    <th class="pb-3 text-right">AI Trust</th>
                                    <th class="pb-3 text-right">Score</th>
                                </tr>
                            </thead>
                            <tbody class="text-slate-300">
                                {"".join([f'<tr class="border-t border-slate-800 hover:bg-slate-800/30"><td class="py-2 font-medium text-blue-400">{i["id"]}</td><td class="text-right text-emerald-400">{i["profit_h"]:,}</td><td class="text-right">{i["trust"]}</td><td class="text-right font-bold text-white">{i["score"]:,}</td></tr>' for i in bot.market_cache[:8]])}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <!-- Col 4: Minecraft mod status + task queue + log -->
            <div class="space-y-6">

                <!-- Mod live status -->
                <div class="glass p-6">
                    <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">🎮 Minecraft Mod</h3>
                    <div class="space-y-3 text-sm">
                        <div class="flex justify-between">
                            <span class="text-slate-400">GUI</span>
                            <span class="text-white font-mono text-xs">{bot.mc_gui or '—'}</span>
                        </div>
                        <div class="flex justify-between border-t border-slate-700 pt-2">
                            <span class="text-slate-400">Coins</span>
                            <span class="text-yellow-400">{bot.mc_coins:,.0f}</span>
                        </div>
                        <div class="border-t border-slate-700 pt-2">
                            <span class="text-slate-400 block mb-1">Inventory (top)</span>
                            <span class="text-xs text-slate-300 font-mono">{inv_preview}</span>
                        </div>
                    </div>
                </div>

                <!-- Current task + queue -->
                <div class="glass p-6">
                    <h3 class="text-slate-400 text-sm font-semibold uppercase mb-3">📋 Task Queue</h3>
                    <div class="mb-3 p-3 rounded-lg" style="background:rgba(16,185,129,0.1); border:1px solid rgba(16,185,129,0.2)">
                        <div class="text-xs text-slate-400 mb-1">Current</div>
                        <div class="font-bold text-emerald-400">{current_task['action']}</div>
                        <div class="text-xs text-slate-300">{current_task.get('item_id','') or '—'} {f"× {current_task['amount']}" if current_task.get('amount') else ''}</div>
                    </div>
                    <div class="text-xs text-slate-500 mb-2">Queued ({len(queued_tasks)})</div>
                    <table class="w-full text-xs">
                        <thead class="text-slate-500"><tr><th class="text-left pb-1">Action</th><th class="text-left pb-1">Item</th><th class="text-right pb-1">Qty</th><th class="text-right pb-1">Price</th></tr></thead>
                        <tbody>{task_rows or '<tr><td colspan="4" class="py-3 text-slate-600 italic text-center">Queue empty</td></tr>'}</tbody>
                    </table>
                </div>

                <!-- Action log -->
                <div class="glass p-6">
                    <h3 class="text-slate-400 text-sm font-semibold uppercase mb-3">📜 Activity Log</h3>
                    <div style="max-height:280px;overflow-y:auto">{log_html}</div>
                </div>
            </div>

        </div>
    </div>
    </body>
    </html>
    """


# ==============================================================================
#  PŮVODNÍ AKCE (nezměněno)
# ==============================================================================

@app.post("/update_settings")
async def update_settings(mode: str = Form(...), focus_id: str = Form(...), budget: float = Form(...)):
    bot.mode = mode
    bot.focus_item = focus_id.upper()
    if not bot.is_running:
        bot.cash = budget
    return RedirectResponse(url="/dashboard", status_code=303)

@app.get("/toggle")
async def toggle():
    bot.is_running = not bot.is_running
    bot.log(f"System {'started' if bot.is_running else 'stopped'}")
    return RedirectResponse(url="/dashboard", status_code=303)

@app.get("/panic")
async def panic():
    bot.is_running = False
    bot.active_orders = {}
    with bot._task_lock:
        bot._task_queue.clear()
        bot._current_task = _idle_task()
    bot.log("🚨 PANIC — all orders and tasks cleared")
    return RedirectResponse(url="/dashboard", status_code=303)


# ==============================================================================
#  START
# ==============================================================================

threading.Thread(target=update_loop, daemon=True).start()

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
