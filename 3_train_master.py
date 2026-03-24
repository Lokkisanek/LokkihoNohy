import pandas as pd
import numpy as np
import gymnasium as gym
from gymnasium import spaces
from stable_baselines3 import PPO
from stable_baselines3.common.vec_env import SubprocVecEnv # Pro paralelní běh
from stable_baselines3.common.utils import set_random_seed
import glob
import random
import os

# Třída prostředí zůstává stejná
class BazaarGodEnv(gym.Env):
    def __init__(self, csv_folder, window_size=20):
        super().__init__()
        all_files = glob.glob(f"{csv_folder}/*.csv")
        self.csv_files = [f for f in all_files if os.path.getsize(f) > 5000] # Jen větší soubory
        self.window_size = window_size
        self.action_space = spaces.Discrete(3) 
        self.observation_space = spaces.Box(low=-50, high=50, shape=(window_size + 4,), dtype=np.float32)

    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        df = pd.read_csv(random.choice(self.csv_files))
        df = df.ffill().fillna(1.0)
        self.prices = df['Sell_Price'].values.astype(np.float32)
        self.buy_prices = df['Buy_Price'].values.astype(np.float32)
        self.max_steps = len(self.prices) - 1
        self.current_step = self.window_size
        self.initial_balance = 1000000.0
        self.balance = self.initial_balance
        self.inventory = 0
        self.net_worth = self.initial_balance
        self.tax_rate = 0.0125
        return self._get_observation(), {}

    def _get_observation(self):
        window = self.prices[self.current_step - self.window_size : self.current_step]
        sma = np.mean(window) if np.mean(window) > 0 else 1.0
        norm_prices = (window / sma) - 1.0
        volatility = np.std(window) / sma
        inv_pct = (self.inventory * self.prices[self.current_step-1]) / (self.net_worth + 1e-6)
        trend = (self.prices[self.current_step-1] - sma) / sma
        obs = np.concatenate([norm_prices, [volatility], [self.balance / self.initial_balance], [inv_pct], [trend]]).astype(np.float32)
        return np.nan_to_num(obs, nan=0.0, posinf=1.0, neginf=-1.0)

    def step(self, action):
        buy_p = self.buy_prices[self.current_step]
        sell_p = self.prices[self.current_step]
        prev_nw = self.net_worth
        qty = 100
        
        fee = 0
        if action == 1 and self.balance >= buy_p * qty:
            self.balance -= buy_p * qty
            self.inventory += qty
            fee = 0.05
        elif action == 2 and self.inventory >= qty:
            self.balance += (sell_p * qty) * (1 - self.tax_rate)
            self.inventory -= qty
            fee = 0.05

        self.current_step += 1
        curr_p = self.prices[self.current_step]
        self.net_worth = self.balance + (self.inventory * curr_p * (1 - self.tax_rate))
        reward = ((self.net_worth - prev_nw) / prev_nw) * 100.0
        reward -= fee
        if action == 0: reward -= 0.005
        
        done = self.current_step >= self.max_steps
        return self._get_observation(), reward, done, False, {}

# Pomocná funkce pro paralelní prostředí
def make_env(csv_folder, rank, seed=0):
    def _init():
        env = BazaarGodEnv(csv_folder)
        env.reset(seed=seed + rank)
        return env
    set_random_seed(seed)
    return _init

if __name__ == "__main__":
    data_path = "market_data_csv"
    
    # --- GPU TUNING ---
    # Počet paralelních oken (podle počtu jader tvého CPU, např. 8 nebo 12)
    num_cpu = 8 
    
    print(f"🚀 Startuji paralelní trénink na {num_cpu} jádrech s podporou GPU (CUDA)...")
    
    # Vytvoření vektorizovaného prostředí
    env = SubprocVecEnv([make_env(data_path, i) for i in range(num_cpu)])
    
    model = PPO(
        "MlpPolicy", 
        env, 
        verbose=1, 
        learning_rate=0.0002, 
        n_steps=2048, # Počet kroků na jedno jádro před updatem
        batch_size=512, # Větší batch size pro GPU (GPU miluje velké dávky dat)
        device="cuda",  # <--- TADY ŘÍKÁME, AŤ TO BĚŽÍ NA GRAFICE
        tensorboard_log="./ppo_bazaar_tensorboard/"
    )
    
    try:
        # Trénujeme na 5 000 000 kroků (s GPU to poletí)
        model.learn(total_timesteps=5000000, tb_log_name="PPO_GPU_V5") 
        model.save("universal_bazaar_brain_gpu")
        print("✅ Master mozek uložen.")
    except KeyboardInterrupt:
        model.save("universal_bazaar_brain_gpu_interrupted")
        print("🛑 Přerušeno, uloženo.")