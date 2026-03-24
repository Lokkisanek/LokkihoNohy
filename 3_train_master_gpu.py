import pandas as pd
import numpy as np
import gymnasium as gym
from gymnasium import spaces
from stable_baselines3 import PPO
from stable_baselines3.common.vec_env import SubprocVecEnv
from stable_baselines3.common.monitor import Monitor # DŮLEŽITÉ PRO DATA
from stable_baselines3.common.utils import set_random_seed
import glob
import random
import os

EPS = 1e-8

class BazaarScalperEnv(gym.Env):
    def __init__(self, csv_folder, window_size=30):
        super().__init__()
        all_files = glob.glob(f"{csv_folder}/*.csv")
        self.csv_files = [f for f in all_files if os.path.getsize(f) > 10000]
        self.window_size = window_size
        self.action_space = spaces.Discrete(3) 
        self.observation_space = spaces.Box(low=-100, high=100, shape=(window_size + 5,), dtype=np.float32)

    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        df = pd.read_csv(random.choice(self.csv_files))
        df = df.ffill().fillna(1.0)
        df['Sell_Price'] = df['Sell_Price'].replace(0, 1.0)
        df['Buy_Price'] = df['Buy_Price'].replace(0, 1.0)
        
        self.prices_sell = df['Buy_Price'].values.astype(np.float32) 
        self.prices_buy = df['Sell_Price'].values.astype(np.float32) 
        self.max_steps = len(self.prices_buy) - 1
        self.current_step = self.window_size
        self.balance = 1000000.0
        self.inventory = 0
        self.buy_price_at_entry = 1.0 
        self.steps_held = 0
        return self._get_observation(), {}

    def _get_observation(self):
        window = self.prices_buy[self.current_step - self.window_size : self.current_step]
        sma = np.mean(window) if np.mean(window) > 0 else 1.0
        norm_prices = (window / sma) - 1.0
        current_buy = self.prices_buy[self.current_step]
        current_sell = self.prices_sell[self.current_step]
        spread = (current_sell - current_buy) / (current_buy + EPS)
        obs = np.concatenate([norm_prices, [np.clip(spread, -1, 1)], [np.clip(np.std(window) / sma, 0, 1)], 
                              [float(self.inventory > 0)], [self.steps_held / 100.0], [self.balance / 1000000.0]]).astype(np.float32)
        return np.nan_to_num(obs)

    def step(self, action):
        buy_p = max(1.0, self.prices_buy[self.current_step])
        sell_p = max(1.0, self.prices_sell[self.current_step])
        reward = 0.0
        qty = 64 
        
        if action == 1 and self.balance >= buy_p * qty and self.inventory == 0:
            self.balance -= buy_p * qty
            self.inventory = qty
            self.buy_price_at_entry = buy_p
            self.steps_held = 0
            reward = 0.01 
        elif action == 2 and self.inventory > 0:
            final_revenue = (sell_p * self.inventory) * 0.9875
            profit_pct = (final_revenue - (self.buy_price_at_entry * self.inventory)) / (self.buy_price_at_entry * self.inventory + EPS)
            # KLÍČOVÁ ZMĚNA: Reward škálujeme na malá čísla (-1 až +1)
            reward = np.clip(profit_pct * 20.0, -1.0, 1.0)
            self.balance += final_revenue
            self.inventory = 0
            self.steps_held = 0

        if self.inventory > 0:
            self.steps_held += 1
            reward -= 0.005 * self.steps_held # Mírnější trest
        
        self.current_step += 1
        # Ukončíme po 500 krocích pro extrémně rychlá data v TensorBoardu
        done = (self.current_step >= self.max_steps) or (self.current_step >= self.window_size + 500)
        return self._get_observation(), float(reward), done, False, {}

# TADY JE TA OPRAVA PRO DATA
def make_env(csv_folder, rank, seed=0):
    def _init():
        env = BazaarScalperEnv(csv_folder)
        # Zabalíme každé vlákno do Monitoru, aby posílalo data do TensorBoardu
        return Monitor(env)
    set_random_seed(seed)
    return _init

if __name__ == "__main__":
    data_path = "market_data_csv"
    num_cpu = 8 
    print(f"🔥 STARTUJI BAZAAR SCALPER V3 (FIXED LOGGING)...")
    env = SubprocVecEnv([make_env(data_path, i) for i in range(num_cpu)])
    
    model = PPO(
        "MlpPolicy", env, verbose=1, learning_rate=0.0003, n_steps=1024, batch_size=256,
        ent_coef=0.05, device="cuda",
        # Menší síť je paradoxně na scalping někdy stabilnější
        policy_kwargs=dict(net_arch=dict(pi=[128, 128], vf=[128, 128])),
        tensorboard_log="./ppo_bazaar_tensorboard/"
    )
    
    try:
        model.learn(total_timesteps=5000000, tb_log_name="SCALPER_V3_FINAL") 
        model.save("bazaar_scalper_brain_v3")
        print("✅ Hotovo!")
    except KeyboardInterrupt:
        model.save("bazaar_scalper_brain_v3_partial")