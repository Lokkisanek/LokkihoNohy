import requests, time, numpy as np
from stable_baselines3 import PPO

# Konfigurace
BUDGET = 50_000_000  # Tvůj aktuální budget v Minecraftu
MIN_VELOCITY = 50   # Minimum insta-obchodů za hodinu, aby se bot nezasekl

model = PPO.load("bazaar_scalper_brain_v3")

def get_best_flip():
    data = requests.get("https://api.hypixel.net/v2/skyblock/bazaar").json()["products"]
    candidates = []

    for item_id, info in data.items():
        qs = info["quick_status"]
            
        # 1. Základní filtrace
        buy_p = qs["buyPrice"]   # Cena pro Sell Offer
        sell_p = qs["sellPrice"] # Cena pro Buy Order
        if sell_p > BUDGET or sell_p == 0: continue
        
        # 2. Volume analýza (Velocity)
        # Sledujeme moving average za hodinu (z API dat)
        buys_h = qs.get("buyMovingWeek", 0) / 168 # Aproximace hodinových insta-nákupů
        sells_h = qs.get("sellMovingWeek", 0) / 168 
        velocity = min(buys_h, sells_h)
        
        if velocity < MIN_VELOCITY: continue

        # 3. AI Trust Factor
        # Tady AI analyzuje graf a řekne, jestli je to bezpečné
        # (Vezmeme reálnou historii, kterou jsme stáhli dřív)
        prediction_score = analyze_with_ai(item_id, sell_p, buy_p)
        
        # 4. Výpočet reálného hodinového profitu (Coins Per Hour)
        margin = (buy_p * 0.9875) - sell_p
        potential_cph = margin * velocity
        
        # Finální skóre kombinované s AI
        final_score = potential_cph * prediction_score
        
        candidates.append({
            "id": item_id,
            "score": final_score,
            "buy_at": sell_p + 0.1,
            "sell_at": buy_p - 0.1,
            "trust": prediction_score
        })

    # Vrátíme nejlepší item
    return sorted(candidates, key=lambda x: x["score"], reverse=True)[0]

def analyze_with_ai(item_id, sell_p, buy_p):
    # AI se podívá na spread a trend
    # Simulujeme výstup z tvého natrénovaného PPO modelu
    obs = np.array([...]) # Zde vložíš normalizovaná data z historie itemu
    action, _states = model.predict(obs, deterministic=True)
    
    # Pokud AI řekne "Koupit" (1), trust je vysoký. Pokud "Čekat" (0), trust je nízký.
    return 0.95 if action == 1 else 0.1