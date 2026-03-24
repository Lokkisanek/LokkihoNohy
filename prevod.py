import torch
from stable_baselines3 import PPO

# 1. Načtení modelu
model = PPO.load("bazaar_scalper_brain_v3")

# 2. Vytvoření dummy vstupu (stejný tvar jako tvůj observation space)
# Tvar je [1, 35] -> (window_size 30 + 5 dalších hodnot)
dummy_input = torch.randn(1, 35)

# 3. Export do ONNX
torch.onnx.export(
    model.policy, 
    dummy_input, 
    "bazaar_brain.onnx", 
    verbose=True,
    input_names=['input'],
    output_names=['output']
)
print("✅ AI mozek exportován do bazaar_brain.onnx!")