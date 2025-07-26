from database.connection import engine, Base
from models.transaction import Transaction, TransactionReview, ReviewStatus

def create_tables():
    Base.metadata.create_all(bind=engine)
    print("Database tables created successfully!")

if __name__ == "__main__":
    create_tables()