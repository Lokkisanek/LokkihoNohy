from fastapi import FastAPI
import numpy as np
from stable_baselines3 import PPO
import uvicorn

app = FastAPI()
model = PPO.load("bazaar_scalper_brain_v3")

# Buffer pro historii cen (protože mozek potřebuje okno 30 cen)
price_histories = {} 

@app.post("/predict")
async def predict(data: dict):
    item_id = data['item_id']
    sell_p = data['sell_price']
    buy_p = data['buy_price']
    balance = data['balance']
    inventory = data['has_item'] # 0 nebo 1
    
    # Udržujeme historii pro každý item zvlášť
    if item_id not in price_histories:
        price_histories[item_id] = [sell_p] * 30
    
    price_histories[item_id].append(sell_p)
    if len(price_histories[item_id]) > 30:
        price_histories[item_id].pop(0)
        
    # Příprava dat pro mozek (stejně jako v tréninku)
    history = np.array(price_histories[item_id])
    sma = np.mean(history)
    norm_prices = (history / sma) - 1.0
    spread = (buy_p - sell_p) / (sell_p + 1e-8)
    
    obs = np.concatenate([
        norm_prices,
        [np.clip(spread, -1, 1)],
        [np.clip(np.std(history)/sma, 0, 1)],
        [float(inventory)],
        [0.0], # steps_held (pro zjednodušení)
        [balance / 1000000.0]
    ]).astype(np.float32)
    
    action, _ = model.predict(obs, deterministic=True)
    
    return {"action": int(action)}

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=5000)