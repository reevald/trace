from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session, joinedload
from sqlalchemy import desc, func
from typing import List, Optional
from datetime import datetime
from database.connection import get_db
from models.transaction import Transaction, TransactionReview, ReviewStatus
from api.schemas import (
    TransactionResponse, 
    SuspiciousTransactionResponse, 
    ReviewStatusUpdate,
    PaginatedResponse
)

router = APIRouter()

@router.get("/transactions", response_model=PaginatedResponse)
def get_transactions(
    page: int = Query(1, ge=1),
    size: int = Query(10, ge=1, le=100),
    transaction_type: Optional[str] = Query(None),
    db: Session = Depends(get_db)
):
    query = db.query(Transaction)
    
    if transaction_type:
        query = query.filter(Transaction.transaction_type == transaction_type)
    
    total = query.count()
    transactions = query.offset((page - 1) * size).limit(size).all()
    
    return PaginatedResponse(
        items=[TransactionResponse.from_orm(tx).dict() for tx in transactions],
        total=total,
        page=page,
        size=size,
        pages=(total + size - 1) // size
    )

@router.get("/suspicious-transactions", response_model=PaginatedResponse)
def get_suspicious_transactions(
    page: int = Query(1, ge=1),
    size: int = Query(10, ge=1, le=100),
    status: Optional[str] = Query(None),
    min_risk_score: Optional[float] = Query(None),
    db: Session = Depends(get_db)
):
    query = db.query(TransactionReview).options(
        joinedload(TransactionReview.transaction),
        joinedload(TransactionReview.review_status)
    ).filter(TransactionReview.is_suspicious == True)
    
    if status:
        query = query.join(ReviewStatus).filter(ReviewStatus.status == status)
    
    if min_risk_score:
        query = query.filter(TransactionReview.risk_score >= min_risk_score)
    
    query = query.order_by(desc(TransactionReview.risk_score))
    
    total = query.count()
    reviews = query.offset((page - 1) * size).limit(size).all()
    
    items = []
    for review in reviews:
        items.append({
            "transaction": TransactionResponse.from_orm(review.transaction).dict(),
            "review": {
                "id": review.id,
                "risk_score": review.risk_score,
                "pattern_type": review.pattern_type,
                "community_id": review.community_id,
                "cycle_length": review.cycle_length,
                "is_suspicious": review.is_suspicious,
                "analysis_details": review.analysis_details,
                "created_at": review.created_at
            },
            "review_status": {
                "id": review.review_status.id if review.review_status else None,
                "status": review.review_status.status if review.review_status else "not_reviewed",
                "comment": review.review_status.comment if review.review_status else None,
                "reviewer": review.review_status.reviewer if review.review_status else None,
                "reviewed_at": review.review_status.reviewed_at if review.review_status else None,
                "created_at": review.review_status.created_at if review.review_status else None
            }
        })
    
    return PaginatedResponse(
        items=items,
        total=total,
        page=page,
        size=size,
        pages=(total + size - 1) // size
    )

@router.get("/transaction-review/{review_id}")
def get_transaction_review(review_id: int, db: Session = Depends(get_db)):
    review = db.query(TransactionReview).options(
        joinedload(TransactionReview.transaction),
        joinedload(TransactionReview.review_status)
    ).filter(TransactionReview.id == review_id).first()
    
    if not review:
        raise HTTPException(status_code=404, detail="Transaction review not found")
    
    return {
        "transaction": TransactionResponse.from_orm(review.transaction).dict(),
        "review": {
            "id": review.id,
            "risk_score": review.risk_score,
            "pattern_type": review.pattern_type,
            "community_id": review.community_id,
            "cycle_length": review.cycle_length,
            "is_suspicious": review.is_suspicious,
            "analysis_details": review.analysis_details,
            "created_at": review.created_at
        },
        "review_status": {
            "id": review.review_status.id if review.review_status else None,
            "status": review.review_status.status if review.review_status else "not_reviewed",
            "comment": review.review_status.comment if review.review_status else None,
            "reviewer": review.review_status.reviewer if review.review_status else None,
            "reviewed_at": review.review_status.reviewed_at if review.review_status else None,
            "created_at": review.review_status.created_at if review.review_status else None
        }
    }

@router.put("/transaction-review/{review_id}/status")
def update_review_status(
    review_id: int, 
    status_update: ReviewStatusUpdate, 
    db: Session = Depends(get_db)
):
    review = db.query(TransactionReview).filter(TransactionReview.id == review_id).first()
    if not review:
        raise HTTPException(status_code=404, detail="Transaction review not found")
    
    review_status = db.query(ReviewStatus).filter(
        ReviewStatus.transaction_review_id == review_id
    ).first()
    
    if review_status:
        review_status.status = status_update.status
        review_status.comment = status_update.comment
        review_status.reviewer = status_update.reviewer
        if status_update.status == "reviewed":
            review_status.reviewed_at = datetime.utcnow()
    else:
        review_status = ReviewStatus(
            transaction_review_id=review_id,
            status=status_update.status,
            comment=status_update.comment,
            reviewer=status_update.reviewer,
            reviewed_at=datetime.utcnow() if status_update.status == "reviewed" else None
        )
        db.add(review_status)
    
    db.commit()
    db.refresh(review_status)
    
    return {"message": "Review status updated successfully", "status": review_status.status}

@router.get("/analytics/summary")
def get_analytics_summary(db: Session = Depends(get_db)):
    total_transactions = db.query(Transaction).count()
    suspicious_transactions = db.query(TransactionReview).filter(
        TransactionReview.is_suspicious == True
    ).count()
    
    reviewed_count = db.query(ReviewStatus).filter(
        ReviewStatus.status == "reviewed"
    ).count()
    
    pending_review = suspicious_transactions - reviewed_count
    
    # Risk score distribution
    high_risk = db.query(TransactionReview).filter(
        TransactionReview.risk_score >= 80,
        TransactionReview.is_suspicious == True
    ).count()
    
    medium_risk = db.query(TransactionReview).filter(
        TransactionReview.risk_score >= 60,
        TransactionReview.risk_score < 80,
        TransactionReview.is_suspicious == True
    ).count()
    
    low_risk = db.query(TransactionReview).filter(
        TransactionReview.risk_score < 60,
        TransactionReview.is_suspicious == True
    ).count()
    
    # Pattern type distribution
    pattern_stats = db.query(
        TransactionReview.pattern_type,
        func.count(TransactionReview.id).label('count')
    ).filter(
        TransactionReview.is_suspicious == True
    ).group_by(TransactionReview.pattern_type).all()
    
    return {
        "total_transactions": total_transactions,
        "suspicious_transactions": suspicious_transactions,
        "reviewed_transactions": reviewed_count,
        "pending_review": pending_review,
        "risk_distribution": {
            "high_risk": high_risk,
            "medium_risk": medium_risk,
            "low_risk": low_risk
        },
        "pattern_distribution": {pattern.pattern_type: pattern.count for pattern in pattern_stats}
    }

@router.get("/network-graph/{review_id}")
def get_network_graph(review_id: int, db: Session = Depends(get_db)):
    review = db.query(TransactionReview).options(
        joinedload(TransactionReview.transaction)
    ).filter(TransactionReview.id == review_id).first()
    
    if not review:
        raise HTTPException(status_code=404, detail="Transaction review not found")
    
    # Simple network representation
    nodes = [
        {"id": review.transaction.from_address, "label": review.transaction.from_address[:10] + "..."},
        {"id": review.transaction.to_address, "label": review.transaction.to_address[:10] + "..."}
    ]
    
    edges = [
        {
            "from": review.transaction.from_address,
            "to": review.transaction.to_address,
            "amount": review.transaction.amount,
            "tx_id": review.transaction.tx_id
        }
    ]
    
    return {
        "nodes": nodes,
        "edges": edges,
        "pattern_type": review.pattern_type,
        "community_id": review.community_id,
        "cycle_length": review.cycle_length
    }