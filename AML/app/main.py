from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware
from database.connection import engine
from models.transaction import Base
from api.endpoints import router
import uvicorn

# Create database tables
Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="AML API",
    description="Anti-Money Laundering API for transaction monitoring and analysis",
    version="1.0.0"
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include API routes
app.include_router(router, prefix="/api/aml", tags=["AML"])

@app.get("/")
def read_root():
    return {"message": "AML API is running", "version": "1.0.0"}

@app.get("/health")
def health_check():
    return {"status": "healthy"}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)