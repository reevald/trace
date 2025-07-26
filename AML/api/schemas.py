from pydantic import BaseModel
from datetime import datetime
from typing import Optional, List

class TransactionBase(BaseModel):
    tx_id: str
    type_tx: str
    amount: float
    from_address: str
    to_address: str
    status: str
    transaction_type: str

class TransactionResponse(TransactionBase):
    id: int
    requested_date: datetime
    updated_date: datetime
    created_at: datetime
    
    class Config:
        from_attributes = True

class TransactionReviewBase(BaseModel):
    risk_score: float
    pattern_type: str
    community_id: Optional[str] = None
    cycle_length: Optional[int] = None
    is_suspicious: bool
    analysis_details: str

class TransactionReviewResponse(TransactionReviewBase):
    id: int
    transaction_id: int
    created_at: datetime
    
    class Config:
        from_attributes = True

class ReviewStatusBase(BaseModel):
    status: str
    comment: Optional[str] = None
    reviewer: Optional[str] = None

class ReviewStatusResponse(ReviewStatusBase):
    id: int
    transaction_review_id: int
    reviewed_at: Optional[datetime] = None
    created_at: datetime
    
    class Config:
        from_attributes = True

class ReviewStatusUpdate(BaseModel):
    status: str
    comment: Optional[str] = None
    reviewer: Optional[str] = None

class SuspiciousTransactionResponse(BaseModel):
    transaction: TransactionResponse
    review: TransactionReviewResponse
    review_status: ReviewStatusResponse
    
    class Config:
        from_attributes = True

class PaginatedResponse(BaseModel):
    items: List[dict]
    total: int
    page: int
    size: int
    pages: int