import json
import pandas as pd
import matplotlib.pyplot as plt

def process_bazaar_data():
    print("Načítám 58 000 řádků z JSONu...")
    with open("bazaar_dump.json", "r", encoding="utf-8") as f:
        data = json.load(f)

    # 1. NAJDEME HISTORII (Hledáme to obrovské pole polí)
    history_key = None
    for key, value in data.items():
        if isinstance(value, list) and len(value) > 100 and isinstance(value[0], list):
            history_key = key
            break

    if not history_key:
        print("Nenašel jsem pole s historií! Zkontroluj strukturu JSONu.")
        return

    raw_history = data[history_key]
    print(f"Nalezena komprimovaná historie: {len(raw_history)} časových záznamů.")

    # 2. ROZŠIFROVÁNÍ (De-komprese Delt)
    print("Dekóduji data...")
    decoded_data = []
    
    # První řádek jsou startovací, reálné hodnoty
    current_values = list(raw_history[0])
    decoded_data.append(current_values.copy())
    
    # Každý další řádek obsahuje jen změnu (deltu) oproti minulému řádku
    for i in range(1, len(raw_history)):
        delta_row = raw_history[i]
        for j in range(len(delta_row)):
            current_values[j] += delta_row[j]  # Přičteme rozdíl
        decoded_data.append(current_values.copy())

    # 3. TVORBA TABULKY (Pandas DataFrame)
    # Nevíme přesně, co každý sloupeček znamená, ale z logiky trhu to bývá:
    # Čas, Buy Price, Sell Price, Volume... Ostatní nazveme prostě "Sloupec_X"
    col_names = ["Čas (Index)", "Buy_Price", "Sell_Price", "Sloupec_3", "Sloupec_4", "Sloupec_5", "Sloupec_6"]
    
    # Zarovnáme počet sloupců
    num_cols = len(decoded_data[0])
    if num_cols > len(col_names):
        for i in range(len(col_names), num_cols):
            col_names.append(f"Sloupec_{i}")
    else:
        col_names = col_names[:num_cols]

    df = pd.DataFrame(decoded_data, columns=col_names)

    # 4. ULOŽENÍ DO EXCELU/CSV (Pro AI a tvůj průzkum)
    csv_filename = "ENCHANTED_DIAMOND_cista_data.csv"
    df.to_csv(csv_filename, index=False)
    print(f"\n✅ ÚSPĚCH! Data uložena do: {csv_filename}")
    
# --- Vykreslení reálného grafu (BUY i SELL) ---
    print("Kreslím graf s Buy i Sell cenou...")
    plt.figure(figsize=(14, 7)) # Trochu to zvětšíme
    
    # 1. Křivka: Buy Price (Za kolik se dá item insta-koupit) - ČERVENĚ
    plt.plot(df["Buy_Price"], label="Buy Price (Insta-Buy / Sell Offer)", color="red", linewidth=1.5, alpha=0.8)
    
    # 2. Křivka: Sell Price (Za kolik se dá item insta-prodat) - ZELENĚ
    plt.plot(df["Sell_Price"], label="Sell Price (Insta-Sell / Buy Order)", color="green", linewidth=1.5, alpha=0.8)
    
    # Křivkám můžeme i vyplnit prostor mezi nimi (tohle je ten tvůj hrubý PROFIT MARGIN!)
    plt.fill_between(df.index, df["Sell_Price"], df["Buy_Price"], color='grey', alpha=0.2, label="Profit Spread (Margin)")

    # Design grafu
    plt.title("Historický vývoj a Profit Spread - Enchanted Diamond", fontsize=14, fontweight='bold')
    plt.xlabel("Časová osa (od nejstaršího po nejnovější)")
    plt.ylabel("Cena (Coins)")
    plt.legend(loc="upper left")
    plt.grid(True, linestyle="--", alpha=0.5)
    
    # Zobrazí okno s grafem
    plt.tight_layout()
    plt.show()

# Spustíme to
# process_bazaar_data()
# Spustíme to
process_bazaar_data()