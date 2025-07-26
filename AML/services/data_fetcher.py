import requests
import time
import schedule
from datetime import datetime
from sqlalchemy.orm import Session
from database.connection import SessionLocal
from models.transaction import Transaction
from config.settings import OBSERVER_API_BASE, FETCH_INTERVAL

class DataFetcher:
    def __init__(self):
        self.base_url = OBSERVER_API_BASE
        
    def fetch_transactions(self, endpoint: str, transaction_type: str):
        try:
            response = requests.get(f"{self.base_url}/{endpoint}")
            response.raise_for_status()
            data = response.json()
            
            db = SessionLocal()
            try:
                for tx_data in data.get('content', []):
                    existing_tx = db.query(Transaction).filter(
                        Transaction.tx_id == tx_data['txId']
                    ).first()
                    
                    if not existing_tx:
                        transaction = Transaction(
                            tx_id=tx_data['txId'],
                            type_tx=tx_data['typeTx'],
                            amount=tx_data['amount'],
                            from_address=tx_data['from'],
                            to_address=tx_data['to'],
                            requested_date=datetime.fromisoformat(tx_data['requestedDate'].replace('Z', '+00:00')),
                            updated_date=datetime.fromisoformat(tx_data['updatedDate'].replace('Z', '+00:00')),
                            status=tx_data['status'],
                            transaction_type=transaction_type
                        )
                        db.add(transaction)
                
                db.commit()
                print(f"Fetched and stored {len(data.get('content', []))} {transaction_type} transactions")
                
            except Exception as e:
                db.rollback()
                print(f"Error storing transactions: {e}")
            finally:
                db.close()
                
        except requests.RequestException as e:
            print(f"Error fetching {transaction_type} transactions: {e}")
    
    def fetch_wrd_transactions(self):
        self.fetch_transactions("wrd-transactions", "wRD")
    
    def fetch_rrd_transactions(self):
        self.fetch_transactions("rrd-transactions", "rRD")
    
    def fetch_all_transactions(self):
        print(f"Fetching transactions at {datetime.now()}")
        self.fetch_wrd_transactions()
        self.fetch_rrd_transactions()

def main():
    fetcher = DataFetcher()
    
    # Schedule periodic fetching
    schedule.every(FETCH_INTERVAL).seconds.do(fetcher.fetch_all_transactions)
    
    # Initial fetch
    fetcher.fetch_all_transactions()
    
    print(f"Data fetcher started. Fetching every {FETCH_INTERVAL} seconds...")
    
    while True:
        schedule.run_pending()
        time.sleep(1)

if __name__ == "__main__":
    main()