import requests
import time
import json
import random
from datetime import datetime
from rich.console import Console
from rich.table import Table
from rich.live import Live

console = Console()

# --- KONFIGURACE ---
SERVER_URL = "http://localhost:8000/top"
STARTING_CASH = 10_000_000
FILL_EFFICIENCY = 0.5  # Volume děleno dvěma
CHECK_INTERVAL = 1.0   # 1 vteřina pro HFT pocit

class AIAgentPredator:
    def __init__(self):
        self.cash = STARTING_CASH
        self.inventory = {} 
        self.active_orders = {} 
        self.total_profit = 0

    def get_market_data(self):
        try:
            return requests.get(SERVER_URL, timeout=2).json()
        except:
            return []

    def process_logic(self, top_items):
        for item_id in list(self.active_orders.keys()):
            order = self.active_orders[item_id]
            market_item = next((i for i in top_items if i['id'] == item_id), None)
            
            if not market_item: continue

            # Použijeme .get(), abychom se vyhnuli KeyError
            velocity = market_item.get('velocity', 0)
            
            # Šance na outbid: čím vyšší velocity, tím agresivnější trh
            outbid_chance = min(0.15, (velocity / 8000))
            
            if order['status'] == 'ACTIVE':
                if random.random() < outbid_chance:
                    order['status'] = 'OUTBID'
                    order['relist_timer'] = random.randint(2, 5) # Rychlejší přebíjení
                    continue
                
                # Výpočet plnění (Volume / 2 / 3600s)
                fill_per_sec = (velocity * FILL_EFFICIENCY) / 3600
                actual_fill = int(fill_per_sec) + (1 if random.random() < (fill_per_sec % 1) else 0)
                
                order['current_qty'] += actual_fill
                
                if order['current_qty'] >= order['target_qty']:
                    self.finalize_order(item_id, market_item)

            elif order['status'] == 'OUTBID':
                order['relist_timer'] -= 1
                if order['relist_timer'] <= 0:
                    order['status'] = 'ACTIVE'

    def finalize_order(self, item_id, market_item):
        order = self.active_orders[item_id]
        if order['type'] == 'BUY':
            self.inventory[item_id] = {'qty': order['target_qty'], 'buy_price': market_item['buy_price']}
        else:
            revenue = (market_item['sell_price'] * order['target_qty']) * 0.9875
            profit = revenue - (self.inventory[item_id]['buy_price'] * order['target_qty'])
            self.cash += revenue
            self.total_profit += profit
            if item_id in self.inventory: del self.inventory[item_id]
        
        del self.active_orders[item_id]

    def find_new_opportunities(self, top_items):
        if len(self.active_orders) >= 5: return 

        for item in top_items:
            if item['id'] not in self.active_orders:
                if item['id'] in self.inventory:
                    inv_data = self.inventory[item['id']]
                    self.active_orders[item['id']] = {
                        'type': 'SELL', 'current_qty': 0, 'target_qty': inv_data['qty'],
                        'status': 'ACTIVE', 'relist_timer': 0
                    }
                elif item['trust'] > 0.8:
                    budget = self.cash * 0.25 # Max 2.5M na jeden flip
                    qty = int(budget / item['buy_price'])
                    if qty > 0:
                        self.cash -= (qty * item['buy_price'])
                        self.active_orders[item['id']] = {
                            'type': 'BUY', 'current_qty': 0, 'target_qty': qty,
                            'status': 'ACTIVE', 'relist_timer': 0
                        }
                        break

    def generate_ui(self):
        table = Table(title="🦅 BAZAAR PREDATOR V3 - LIVE SIM", title_style="bold magenta")
        table.add_column("Item", style="cyan", no_wrap=True)
        table.add_column("Mise", style="bold")
        table.add_column("Pozice", style="yellow")
        table.add_column("Naplněno", style="green")
        table.add_column("Stav Peněženky", style="bold white")

        for item_id, o in self.active_orders.items():
            pokrok = f"{o['current_qty']}/{o['target_qty']}"
            pos = "[bold green]TOP #1[/bold green]" if o['status'] == 'ACTIVE' else "[bold red]OUTBIDDED[/bold red]"
            table.add_row(item_id, o['type'], pos, pokrok, "")

        table.add_section()
        table.add_row("Dostupná Hotovost", "", "", "", f"{self.cash:,.0f}")
        table.add_row("Čistý Zisk", "", "", "", f"[bold green]+{self.total_profit:,.0f}[/bold green]")
        
        return table

if __name__ == "__main__":
    agent = AIAgentPredator()
    console.clear()
    
    with Live(agent.generate_ui(), refresh_per_second=2) as live:
        while True:
            market_data = agent.get_market_data()
            if market_data:
                agent.process_logic(market_data)
                agent.find_new_opportunities(market_data)
            
            live.update(agent.generate_ui())
            time.sleep(CHECK_INTERVAL)