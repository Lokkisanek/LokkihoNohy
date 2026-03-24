import pandas as pd
import numpy as np
import gymnasium as gym
from gymnasium import spaces

class BazaarEnv(gym.Env):
    """Vlastní virtuální prostředí pro Hypixel Bazaar"""
    
    def __init__(self, csv_file):
        super(BazaarEnv, self).__init__()
        
        # 1. Načteme naši historii do paměti
        self.df = pd.read_csv(csv_file)
        self.max_steps = len(self.df) - 1
        
        # 2. Nadefinujeme Akce (0: Hold, 1: Insta-Buy, 2: Insta-Sell)
        self.action_space = spaces.Discrete(3)
        
        # 3. Nadefinujeme, co AI vidí (Cena Buy, Cena Sell, Můj zůstatek, Můj Inventář)
        # Používáme Box, což znamená "čísla od 0 do nekonečna"
        self.observation_space = spaces.Box(low=0, high=np.inf, shape=(4,), dtype=np.float32)
        
        # Nastavení účtu
        self.initial_balance = 100000.0  # Dáme AI do začátku 100k coinů
        self.tax_rate = 0.0125           # Bazaar daň 1.25%
        
        self.reset()

    def reset(self, seed=None):
        """Tohle se zavolá na začátku každého tréninku (Vrátí AI na řádek 0)"""
        super().reset(seed=seed)
        self.current_step = 0
        self.balance = self.initial_balance
        self.inventory = 0
        self.net_worth = self.initial_balance
        
        return self._get_observation(), {}

    def _get_observation(self):
        """Vrátí AI aktuální stav světa"""
        # Přečteme aktuální řádek z naší Excel tabulky
        current_row = self.df.iloc[self.current_step]
        
        obs = np.array([
            current_row['Buy_Price'],  # Za kolik můžu koupit
            current_row['Sell_Price'], # Za kolik můžu prodat
            self.balance,              # Kolik mám peněz
            self.inventory             # Kolik mám diamantů v batohu
        ], dtype=np.float32)
        return obs

    def step(self, action):
        """AI udělá jeden tah (akci) a my to vyhodnotíme"""
        current_row = self.df.iloc[self.current_step]
        buy_price = current_row['Buy_Price']
        sell_price = current_row['Sell_Price']
        
        # Uložíme si bohatství před tahem
        prev_net_worth = self.balance + (self.inventory * sell_price * (1 - self.tax_rate))
        
        # --- PROVEDENÍ AKCE ---
        if action == 1: # KOUPI (Nakoupí max 100 kusů, pokud má peníze)
            # Simulujeme nákup od líných hráčů (Buy Order) - reálně získáme item za Sell Price
            # Ale pro zjednodušení teď simulujeme Insta-Buy (platíme dražší Buy_Price)
            cost = buy_price * 100
            if self.balance >= cost:
                self.balance -= cost
                self.inventory += 100
                
        elif action == 2: # PRODEJ (Prodá 100 kusů)
            if self.inventory >= 100:
                # Při prodeji dostaneme levnější Sell Price a platíme daň
                revenue = (sell_price * 100) * (1 - self.tax_rate)
                self.balance += revenue
                self.inventory -= 100
                
        # --- POSUN V ČASE ---
        self.current_step += 1
        
        # Spočítáme nové bohatství
        # (Peníze + hodnota inventáře, kdybychom ho hned střelili)
        current_sell_price = self.df.iloc[self.current_step]['Sell_Price'] if self.current_step < self.max_steps else sell_price
        self.net_worth = self.balance + (self.inventory * current_sell_price * (1 - self.tax_rate))
        
        # ODMĚNA PRO AI (Rozdíl v bohatství. Vydělal = kladná, Prodělal = záporná)
        reward = self.net_worth - prev_net_worth
        
        # Skončili jsme na konci tabulky?
        terminated = self.current_step >= self.max_steps
        truncated = False
        
        info = {'net_worth': self.net_worth}
        
        return self._get_observation(), reward, terminated, truncated, info

# ==========================================
# TEST SIMULÁTORU (Hloupý náhodný trader)
# ==========================================
if __name__ == "__main__":
    # Nahraď názvem svého CSV souboru
    env = BazaarEnv("ENCHANTED_DIAMOND_cista_data.csv")
    
    obs, _ = env.reset()
    print("Startuji Simulátor...")
    print(f"Počáteční stav účtu: {env.balance} coinů")
    
    terminated = False
    # Necháme ho udělat 100 náhodných tahů (řádků v historii)
    for _ in range(100):
        # Vybere náhodně 0 (čekat), 1 (koupit), 2 (prodat)
        random_action = env.action_space.sample() 
        
        obs, reward, terminated, truncated, info = env.step(random_action)
        
        if terminated:
            break
            
    print("\n--- PO 100 NÁHODNÝCH KROCÍCH ---")
    print(f"Konečný stav účtu: {env.balance:.1f} coinů")
    print(f"Itemů na skladě: {env.inventory}")
    print(f"Celkové bohatství (Net Worth): {env.net_worth:.1f} coinů")
    print(f"Zisk/Ztráta: {env.net_worth - env.initial_balance:.1f} coinů")
    
    if env.net_worth < env.initial_balance:
        print("Náhodné obchodování prodělává (kvůli daním a poplatkům). AI to bude muset vymyslet lépe!")