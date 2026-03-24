import json
import pandas as pd
import os
import glob

def decode_all():
    json_files = glob.glob("market_data_json/*.json")
    if not os.path.exists("market_data_csv"): os.makedirs("market_data_csv")

    print(f"Dekóduji {len(json_files)} souborů...")
    for file_path in json_files:
        item_id = os.path.basename(file_path).replace(".json", "")
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                data = json.load(f)

            history_array = None
            for key, val in data.items():
                if isinstance(val, list) and len(val) > 50 and isinstance(val[0], list):
                    history_array = val; break
            
            if not history_array: continue

            # Dekomprese delt
            decoded = []
            current = list(history_array[0])
            decoded.append(current.copy())
            for row in history_array[1:]:
                for i in range(len(row)): current[i] += row[i]
                decoded.append(current.copy())

            df = pd.DataFrame(decoded)
            # Nejdůležitější sloupce: 1=BuyPrice, 2=SellPrice
            df = df[[0, 1, 2]] 
            df.columns = ["Timestamp", "Buy_Price", "Sell_Price"]
            
            df.to_csv(f"market_data_csv/{item_id}.csv", index=False)
            print(f"📊 Hotovo: {item_id}")
        except Exception as e:
            print(f"❌ Chyba u {item_id}: {e}")

if __name__ == "__main__":
    decode_all()