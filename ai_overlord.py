import requests, time, threading, os, uvicorn, random
import numpy as np
import torch # Přidáno pro Mac optimalizaci
from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel # Přidáno pro komunikaci s Javou
from stable_baselines3 import PPO

# --- KONFIGURACE ---
BAZAAR_API = "https://api.hypixel.net/v2/skyblock/bazaar"
MODEL_PATH = "bazaar_scalper_brain_v3.zip"
TAX = 0.0125

# Optimalizace pro Apple Silicon (M1/M2/M3)
device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")

app = FastAPI()

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

bot = BotState()

# Načtení modelu na správné zařízení (zabraňuje přehřívání Macu)
model = PPO.load(MODEL_PATH, device=device) if os.path.exists(MODEL_PATH) else None
price_windows = {}

# --- KOMUNIKAČNÍ MOST S MINECRAFTEM ---
class ItemRequest(BaseModel):
    item_id: str

@app.post("/predict")
async def predict_action(req: ItemRequest):
    """
    Tuto funkci volá Java mód. Posílá, na jaký item v GUI právě kouká.
    Odpověď: 1 = Klikni (Buy/Sell), 0 = Čekej.
    """
    if not bot.is_running:
        return {"action": 0}

    item_id = req.item_id.upper()

    # 1. Kontrola, zda máme tento item v aktivních objednávkách a potřebujeme na něj kliknout
    if item_id in bot.active_orders:
        order = bot.active_orders[item_id]
        # Pokud nás někdo přebil (OUTBIDDED), nebo potřebujeme překliknout z BUY na SELL
        if order["status"] != "TOP #1":
            return {"action": 1} # Říkáme módu: KLIKNI!

    # 2. Hledáme novou příležitost (pokud máme volné sloty)
    if len(bot.active_orders) < 5:
        for best in bot.market_cache:
            if best["id"] == item_id:
                return {"action": 1} # Říkáme módu: KLIKNI (Koupit novou věc)!

    return {"action": 0} # Nic se neděje, říkáme módu: ČEKEJ.


# --- LOGIKA ---
def get_ai_trust(item_id, current_sell):
    if model is None or item_id not in price_windows or len(price_windows[item_id]) < 20: return 0.5
    history = np.array(price_windows[item_id][-20:])
    sma = np.mean(history)
    obs = np.zeros(35, dtype=np.float32)
    obs[:20] = (history / (sma + 1e-8)) - 1.0
    action, _ = model.predict(obs, deterministic=True)
    return 0.98 if action != 0 else 0.1

def update_loop():
    while True:
        try:
            r = requests.get(BAZAAR_API, timeout=10)
            data = r.json().get("products", {})
            temp_results = []
            for item_id, info in data.items():
                qs = info.get("quick_status", {})
                sell_p, buy_p = qs.get("sellPrice", 0), qs.get("buyPrice", 0)
                if sell_p < 5: continue
                if item_id not in price_windows: price_windows[item_id] = [sell_p] * 30
                price_windows[item_id].append(sell_p)
                if len(price_windows[item_id]) > 60: price_windows[item_id].pop(0)
                margin_real = (buy_p * (1 - TAX)) - sell_p
                velocity = (qs.get("buyMovingWeek", 0) + qs.get("sellMovingWeek", 0)) / 336
                trust = get_ai_trust(item_id, sell_p)
                if bot.mode == "FOCUS" and item_id != bot.focus_item: continue
                if bot.mode == "COMFORT" and (trust < 0.9 or velocity < 500): continue
                if bot.mode == "BALANCED" and (trust < 0.7 or velocity < 100): continue
                score = margin_real * velocity * trust
                if score > 1000 or item_id == bot.focus_item:
                    temp_results.append({"id": item_id, "profit_h": int(margin_real * velocity), "trust": round(trust, 2), "score": int(score), "buy_p": sell_p + 0.1, "sell_p": buy_p - 0.1, "vel": velocity})
            bot.market_cache = sorted(temp_results, key=lambda x: x["score"], reverse=True)[:15]
            if bot.is_running:
                for iid, o in list(bot.active_orders.items()):
                    if random.random() < min(0.1, o.get('vel', 0)/10000): o['status'] = "OUTBIDDED"
                    else:
                        o['status'] = "TOP #1"
                        fill_rate = (o.get('vel', 100) * 0.5) / 360
                        o['current'] += max(1, int(fill_rate))
                    if o['current'] >= o['target']:
                        if o['type'] == "BUY": o['type'] = "SELL"; o['current'] = 0
                        else:
                            profit = (o['price'] * o['target'] * (1-TAX)) - (o['buy_price'] * o['target'])
                            bot.cash += (o['price'] * o['target'] * (1-TAX)); bot.total_profit += profit
                            del bot.active_orders[iid]
                if len(bot.active_orders) < 5 and bot.market_cache:
                    for best in bot.market_cache:
                        if best['id'] not in bot.active_orders:
                            qty = int((bot.cash * 0.2) / best['buy_p'])
                            if qty > 0:
                                bot.cash -= (qty * best['buy_p'])
                                bot.active_orders[best['id']] = {"type": "BUY", "current": 0, "target": qty, "status": "TOP #1", "price": best['buy_p'], "buy_price": best['buy_p'], "vel": best['vel']}
                                break
        except Exception as e: print(f"Update error: {e}")
        time.sleep(10)

# --- MODERN WEB UI ---

@app.get("/dashboard", response_class=HTMLResponse)
async def get_dashboard():
    order_cards = ""
    for iid, o in bot.active_orders.items():
        progress = (o['current'] / o['target']) * 100
        status_clr = "#10b981" if o['status'] == "TOP #1" else "#f43f5e"
        type_clr = "#3b82f6" if o['type'] == "BUY" else "#f59e0b"
        
        order_cards += f"""
        <div class="order-card">
            <div class="order-header">
                <span class="order-id">{iid}</span>
                <span class="order-type" style="background: {type_clr}33; color: {type_clr}">{o['type']}</span>
            </div>
            <div class="order-status">
                <span class="dot" style="background: {status_clr}"></span> {o['status']}
            </div>
            <div class="progress-container">
                <div class="progress-bar" style="width: {progress}%"></div>
            </div>
            <div class="order-footer">
                <span>{o['current']} / {o['target']} units</span>
                <span>{int(progress)}%</span>
            </div>
        </div>
        """

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
            .glass {{ background: rgba(30, 41, 59, 0.7); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); border-radius: 16px; }}
            .order-card {{ background: rgba(51, 65, 85, 0.4); border: 1px solid rgba(255,255,255,0.05); padding: 15px; border-radius: 12px; margin-bottom: 12px; }}
            .order-header {{ display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }}
            .order-id {{ font-weight: 700; color: #60a5fa; font-size: 0.9rem; }}
            .order-type {{ font-size: 0.7rem; font-weight: 800; padding: 2px 8px; border-radius: 4px; text-transform: uppercase; }}
            .progress-container {{ background: #1e293b; border-radius: 10px; height: 6px; margin: 10px 0; overflow: hidden; }}
            .progress-bar {{ background: #10b981; height: 100%; transition: width 0.5s ease; }}
            .order-footer {{ display: flex; justify-content: space-between; font-size: 0.75rem; color: #94a3b8; }}
            .dot {{ height: 8px; width: 8px; border-radius: 50%; display: inline-block; margin-right: 5px; }}
            .status-pulse {{ animation: pulse 2s infinite; }}
            @keyframes pulse {{ 0% {{ opacity: 1; }} 50% {{ opacity: 0.4; }} 100% {{ opacity: 1; }} }}
            input, select {{ background: #1e293b !important; border: 1px solid #334155 !important; color: white !important; border-radius: 8px !important; }}
        </style>
    </head>
    <body class="p-4 md:p-10">
        <div class="max-w-6xl mx-auto">
            <!-- Header -->
            <div class="flex justify-between items-center mb-8">
                <div>
                    <h1 class="text-3xl font-bold tracking-tight">🦅 Bazaar Overlord <span class="text-slate-500 font-light">v10</span></h1>
                    <div class="flex items-center mt-1">
                        <span class="dot status-pulse" style="background: {'#10b981' if bot.is_running else '#f43f5e'}"></span>
                        <span class="text-sm font-medium text-slate-400">System {"Active" if bot.is_running else "Standby"} — Mode: {bot.mode}</span>
                    </div>
                </div>
                <div class="flex gap-3">
                    <a href="/toggle" class="px-6 py-2 rounded-xl font-bold transition-all {'bg-rose-500 hover:bg-rose-600' if bot.is_running else 'bg-emerald-500 hover:bg-emerald-600'}">
                        { 'STOP SYSTEM' if bot.is_running else 'START SYSTEM' }
                    </a>
                    <a href="/panic" class="px-6 py-2 rounded-xl font-bold bg-slate-700 hover:bg-rose-700 transition-all border border-rose-500/30">PANIC</a>
                </div>
            </div>

            <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
                <!-- Left: Stats & Controls -->
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
                        </div>
                    </div>

                    <div class="glass p-6">
                        <h3 class="text-slate-400 text-sm font-semibold uppercase mb-4">System Configuration</h3>
                        <form action="/update_settings" method="post" class="space-y-4">
                            <div>
                                <label class="block text-xs text-slate-500 mb-1">Trading Strategy</label>
                                <select name="mode" class="w-full p-2 text-sm">
                                    <option value="AGGRESSIVE" {"selected" if bot.mode=="AGGRESSIVE" else ""}>Aggressive (High Risk)</option>
                                    <option value="BALANCED" {"selected" if bot.mode=="BALANCED" else ""}>Balanced (Medium)</option>
                                    <option value="COMFORT" {"selected" if bot.mode=="COMFORT" else ""}>Comfort (Safe)</option>
                                    <option value="FOCUS" {"selected" if bot.mode=="FOCUS" else ""}>Focus (Specific Item)</option>
                                </select>
                            </div>
                            <div>
                                <label class="block text-xs text-slate-500 mb-1">Focus Item ID</label>
                                <input type="text" name="focus_id" value="{bot.focus_item}" class="w-full p-2 text-sm" placeholder="e.g. IRON_INGOT">
                            </div>
                            <div>
                                <label class="block text-xs text-slate-500 mb-1">Initial Budget</label>
                                <input type="number" name="budget" value="{int(bot.cash)}" class="w-full p-2 text-sm">
                            </div>
                            <button type="submit" class="w-full py-2 bg-blue-600 hover:bg-blue-700 rounded-lg font-semibold text-sm transition-colors">Apply Settings</button>
                        </form>
                    </div>
                </div>

                <!-- Center/Right: Live Orders -->
                <div class="lg:col-span-2 glass p-6">
                    <div class="flex justify-between items-center mb-6">
                        <h3 class="text-slate-400 text-sm font-semibold uppercase">Live Order Execution</h3>
                        <span class="text-xs text-slate-500">{len(bot.active_orders)} active slots used</span>
                    </div>
                    
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        {order_cards if bot.active_orders else '<div class="col-span-2 py-20 text-center text-slate-500 italic">No active orders. System is scanning for opportunities...</div>'}
                    </div>

                    <h3 class="text-slate-400 text-sm font-semibold uppercase mt-10 mb-4">Top Market Opportunities</h3>
                    <div class="overflow-x-auto">
                        <table class="w-full text-sm">
                            <thead class="text-slate-500">
                                <tr>
                                    <th class="pb-3 text-left">Product</th>
                                    <th class="pb-3 text-right">Profit/hr</th>
                                    <th class="pb-3 text-right">AI Trust</th>
                                    <th class="pb-3 text-right">Overlord Score</th>
                                </tr>
                            </thead>
                            <tbody class="text-slate-300">
                                {"".join([f'<tr class="border-t border-slate-800 hover:bg-slate-800/30 transition-colors"><td class="py-3 font-medium text-blue-400">{i["id"]}</td><td class="text-right text-emerald-400">{i["profit_h"]:,}</td><td class="text-right">{i["trust"]}</td><td class="text-right font-bold text-white">{i["score"]:,}</td></tr>' for i in bot.market_cache[:8]])}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        </div>
    </body>
    </html>
    """

# --- API ACTIONS ---
@app.post("/update_settings")
async def update_settings(mode: str = Form(...), focus_id: str = Form(...), budget: float = Form(...)):
    bot.mode = mode
    bot.focus_item = focus_id.upper()
    if not bot.is_running: bot.cash = budget
    return RedirectResponse(url="/dashboard", status_code=303)

@app.get("/toggle")
async def toggle():
    bot.is_running = not bot.is_running
    return RedirectResponse(url="/dashboard", status_code=303)

@app.get("/panic")
async def panic():
    bot.is_running = False
    bot.active_orders = {}
    return RedirectResponse(url="/dashboard", status_code=303)

threading.Thread(target=update_loop, daemon=True).start()

if __name__ == "__main__":
    # Spuštění serveru
    uvicorn.run(app, host="0.0.0.0", port=8000)