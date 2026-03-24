import requests
import time
import os
import json

def mass_scrape():
    # 1. Získáme seznam všech itemů z Hypixelu
    print("Získávám seznam itemů z Hypixelu...")
    items = list(requests.get("https://api.hypixel.net/v2/skyblock/bazaar").json()["products"].keys())
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36",
        "Origin": "https://www.skyblock.bz",
        "Referer": "https://www.skyblock.bz/",
        "X-Requested-With": "XMLHttpRequest"
    }

    if not os.path.exists("market_data_json"): os.makedirs("market_data_json")

    print(f"Nalezeno {len(items)} itemů. Začínám stahování...")
    for item in items:
        path = f"market_data_json/{item}.json"
        if os.path.exists(path): continue
        
        url = f"https://api.skyblock.bz/api/product/init/{item}"
        try:
            res = requests.get(url, headers=headers, timeout=10)
            if res.status_code == 200:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(res.text)
                print(f"✅ Staženo: {item}")
            else:
                print(f"❌ Error {res.status_code} pro {item}")
            time.sleep(1.2) # Ochrana proti banu
        except Exception as e:
            print(f"🔥 Chyba u {item}: {e}")

if __name__ == "__main__":
    mass_scrape()