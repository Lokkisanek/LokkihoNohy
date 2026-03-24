import pandas as pd
import numpy as np
import gymnasium as gym
from gymnasium import spaces
from stable_baselines3 import PPO

class BazaarMasterEnv(gym.Env):
    def __init__(self, csv_file, window_size=10):
        super(BazaarMasterEnv, self).__init__()
        self.df = pd.read_csv(csv_file)
        self.window_size = window_size
        self.max_steps = len(self.df) - 1
        
        # 0=Nic, 1=Buy All-In (nebo hodně), 2=Sell All-In
        self.action_space = spaces.Discrete(3)
        
        # Observation: [Ceny, SMA trend, Balance, Inventory]
        self.observation_space = spaces.Box(low=-np.inf, high=np.inf, shape=(self.window_size + 3,), dtype=np.float32)
        
        self.initial_balance = 100000.0
        self.tax_rate = 0.0125

    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        self.current_step = self.window_size
        self.balance = self.initial_balance
        self.inventory = 0
        self.net_worth = self.initial_balance
        return self._get_observation(), {}

    def _get_observation(self):
        # Vezmeme okno cen
        window = self.df.iloc[self.current_step - self.window_size : self.current_step]
        prices = window['Sell_Price'].values
        
        # Výpočet jednoduchého trendu (SMA)
        sma = np.mean(prices)
        current_price = prices[-1]
        diff_from_sma = (current_price - sma) / sma # % rozdíl od průměru
        
        # Normalizované ceny (posledních X cen)
        norm_prices = prices / 1500.0
        
        obs = np.concatenate([
            norm_prices,
            [diff_from_sma], # Vidí, jestli je levno/dražší než průměr
            [self.balance / 100000.0],
            [self.inventory / 500.0]
        ]).astype(np.float32)
        return obs

    def step(self, action):
        row = self.df.iloc[self.current_step]
        buy_p, sell_p = row['Buy_Price'], row['Sell_Price']
        prev_nw = self.net_worth
        
        # AKCE: Obchodujeme s balíky po 50 kusech (cca 70k coinů)
        trade_qty = 50
        
        if action == 1: # BUY
            if self.balance >= buy_p * trade_qty:
                self.balance -= buy_p * trade_qty
                self.inventory += trade_qty
        elif action == 2: # SELL
            if self.inventory >= trade_qty:
                self.balance += (sell_p * trade_qty) * (1 - self.tax_rate)
                self.inventory -= trade_qty
                
        self.current_step += 1
        
        # Aktuální Net Worth
        curr_sell_p = self.df.iloc[self.current_step]['Sell_Price']
        self.net_worth = self.balance + (self.inventory * curr_sell_p * (1 - self.tax_rate))
        
        # ODMĚNA: Procentuální změna bohatství (mnohem stabilnější pro učení)
        reward = (self.net_worth - prev_nw) / prev_nw * 100.0
        
        # Malý trest za poplatky/neaktivitu
        if action == 0: reward -= 0.01
        
        terminated = self.current_step >= self.max_steps
        return self._get_observation(), reward, terminated, False, {}

if __name__ == "__main__":
    env = BazaarMasterEnv("ENCHANTED_DIAMOND_cista_data.csv")
    
    # Trénink s vylepšenými parametry
    model = PPO("MlpPolicy", env, verbose=1, 
                learning_rate=0.0002, 
                n_steps=4096, 
                batch_size=128)
    
    print("\n--- TRÉNUJI MASTER TRADERA (V3) ---")
    model.learn(total_timesteps=150000)
    
    # TEST
    obs, _ = env.reset()
    for _ in range(env.max_steps - env.window_size):
        action, _ = model.predict(obs, deterministic=True)
        obs, r, term, _, _ = env.step(action)
        if term: break
            
    print(f"\n--- FINÁLNÍ VÝSLEDEK MASTER BOTA ---")
    print(f"Konečné bohatství: {env.net_worth:.1f} coinů")
    print(f"Zisk/Ztráta: {env.net_worth - env.initial_balance:.1f} coinů")