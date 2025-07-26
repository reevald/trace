from sqlalchemy import Column, Integer, String, Float, DateTime, Boolean, Text, ForeignKey
from sqlalchemy.orm import relationship
from database.connection import Base
from datetime import datetime

class Transaction(Base):
    __tablename__ = "transactions"
    
    id = Column(Integer, primary_key=True, index=True)
    tx_id = Column(String, unique=True, index=True)
    type_tx = Column(String)
    amount = Column(Float)
    from_address = Column(String)
    to_address = Column(String)
    requested_date = Column(DateTime)
    updated_date = Column(DateTime)
    status = Column(String)
    transaction_type = Column(String)  # 'wRD' or 'rRD'
    created_at = Column(DateTime, default=datetime.utcnow)
    
    # Relationship to transaction reviews
    reviews = relationship("TransactionReview", back_populates="transaction")

class TransactionReview(Base):
    __tablename__ = "transaction_reviews"
    
    id = Column(Integer, primary_key=True, index=True)
    transaction_id = Column(Integer, ForeignKey("transactions.id"))
    risk_score = Column(Float)
    pattern_type = Column(String)  # 'louvain_community', 'cycle_detection', etc.
    community_id = Column(String, nullable=True)
    cycle_length = Column(Integer, nullable=True)
    is_suspicious = Column(Boolean, default=False)
    analysis_details = Column(Text)
    created_at = Column(DateTime, default=datetime.utcnow)
    
    # Relationships
    transaction = relationship("Transaction", back_populates="reviews")
    review_status = relationship("ReviewStatus", back_populates="transaction_review", uselist=False)

class ReviewStatus(Base):
    __tablename__ = "review_status"
    
    id = Column(Integer, primary_key=True, index=True)
    transaction_review_id = Column(Integer, ForeignKey("transaction_reviews.id"))
    status = Column(String, default="not_reviewed")  # 'reviewed' or 'not_reviewed'
    comment = Column(Text, nullable=True)
    reviewed_at = Column(DateTime, nullable=True)
    reviewer = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    
    # Relationship
    transaction_review = relationship("TransactionReview", back_populates="review_status")