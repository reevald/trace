import os
from dotenv import load_dotenv

load_dotenv()

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://aml_user:aml_password@localhost:5432/aml_db")
OBSERVER_API_BASE = os.getenv("OBSERVER_API_BASE", "http://localhost:10052/api/observer")
FETCH_INTERVAL = int(os.getenv("FETCH_INTERVAL", "30"))
EMAIL_HOST = os.getenv("EMAIL_HOST", "smtp.gmail.com")
EMAIL_PORT = int(os.getenv("EMAIL_PORT", "587"))
EMAIL_USER = os.getenv("EMAIL_USER", "")
EMAIL_PASSWORD = os.getenv("EMAIL_PASSWORD", "")
EMAIL_TO = os.getenv("EMAIL_TO", "admin@example.com")
AML_THRESHOLD = float(os.getenv("AML_THRESHOLD", "10000"))