# AML (Anti-Money Laundering) System

A comprehensive AML system for monitoring and analyzing CBDC transactions using Louvain algorithm and cycle detection to identify suspicious transaction patterns.

## Features

- **Automated Data Fetching**: Pulls wRD and rRD transaction data every 30 seconds from Observer APIs
- **Advanced Analytics**: Uses Louvain community detection and cycle detection algorithms
- **Risk Scoring**: Calculates risk scores for suspicious transaction patterns
- **Review Management**: Track review status of flagged transactions
- **Email Alerts**: Automated email notifications for suspicious activities
- **REST API**: Comprehensive API for transaction monitoring and review
- **Docker Support**: Easy deployment with Docker Compose

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Data Fetcher  │    │  AML Analyzer   │    │ Email Alerter   │
│                 │    │                 │    │                 │
│ Pulls tx data   │    │ Louvain + Cycle │    │ Daily alerts    │
│ every 30s       │    │ Detection       │    │ for suspicious  │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                    ┌─────────────────┐
                    │   PostgreSQL    │
                    │   Database      │
                    └─────────────────┘
                                 │
                    ┌─────────────────┐
                    │    AML API      │
                    │   (FastAPI)     │
                    └─────────────────┘
```

## Quick Start

### Using Docker Compose (Recommended)

1. **Clone and navigate to the project**:
   ```bash
   cd /home/hobiron/PlayDLT/R3Corda/trace/AML
   ```

2. **Configure environment variables**:
   ```bash
   cp .env.example .env
   # Edit .env file with your email settings
   ```

3. **Start all services**:
   ```bash
   docker-compose up -d
   ```

4. **Check service status**:
   ```bash
   docker-compose ps
   ```

5. **Access the API**:
   - API Documentation: http://localhost:8000/docs
   - Health Check: http://localhost:8000/health

### Manual Installation

1. **Install dependencies**:
   ```bash
   pip install -r requirements.txt
   ```

2. **Set up PostgreSQL database**:
   ```bash
   # Create database
   createdb aml_db
   
   # Initialize tables
   python database/init_db.py
   ```

3. **Configure environment**:
   ```bash
   export DATABASE_URL="postgresql://username:password@localhost:5432/aml_db"
   export OBSERVER_API_BASE="http://localhost:10052/api/observer"
   # Add other environment variables as needed
   ```

4. **Start services**:
   ```bash
   # Terminal 1: Start API
   uvicorn app.main:app --host 0.0.0.0 --port 8000
   
   # Terminal 2: Start data fetcher
   python services/data_fetcher.py
   
   # Terminal 3: Start AML analyzer
   python services/aml_analyzer.py
   
   # Terminal 4: Start email alerter
   python services/email_alerter.py
   ```

## API Endpoints

### Base URL: `http://localhost:8000/api/aml`

### Transaction Endpoints

#### Get All Transactions
```http
GET /transactions?page=1&size=10&transaction_type=wRD
```

**Parameters:**
- `page` (int): Page number (default: 1)
- `size` (int): Items per page (default: 10, max: 100)
- `transaction_type` (string, optional): Filter by 'wRD' or 'rRD'

**Response:**
```json
{
  "items": [
    {
      "id": 1,
      "tx_id": "E19E6995B6B73631F932AA0E377904A5240D54A6DF1AB49575730D0473A7EAAD",
      "type_tx": "CENTRAL_INIT",
      "amount": 450.0,
      "from_address": "O=KDR, L=Jakarta, C=ID",
      "to_address": "O=KDR, L=Jakarta, C=ID",
      "status": "COMPLETED",
      "transaction_type": "wRD",
      "requested_date": "2025-01-04T16:15:32.723Z",
      "updated_date": "2025-01-04T16:15:32.723Z",
      "created_at": "2025-01-26T10:30:00Z"
    }
  ],
  "total": 100,
  "page": 1,
  "size": 10,
  "pages": 10
}
```

#### Get Suspicious Transactions
```http
GET /suspicious-transactions?page=1&size=10&status=not_reviewed&min_risk_score=80
```

**Parameters:**
- `page` (int): Page number
- `size` (int): Items per page
- `status` (string, optional): Filter by review status ('reviewed', 'not_reviewed')
- `min_risk_score` (float, optional): Minimum risk score filter

**Response:**
```json
{
  "items": [
    {
      "transaction": {
        "id": 1,
        "tx_id": "ABC123...",
        "amount": 9500.0,
        "from_address": "Address1",
        "to_address": "Address2"
      },
      "review": {
        "id": 1,
        "risk_score": 85.5,
        "pattern_type": "louvain_community",
        "community_id": "comm_001",
        "is_suspicious": true,
        "analysis_details": "Detected community pattern with multiple small transactions"
      },
      "review_status": {
        "id": 1,
        "status": "not_reviewed",
        "comment": null,
        "reviewer": null,
        "reviewed_at": null
      }
    }
  ],
  "total": 25,
  "page": 1,
  "size": 10,
  "pages": 3
}
```

### Review Management Endpoints

#### Get Transaction Review Details
```http
GET /transaction-review/{review_id}
```

**Response:**
```json
{
  "transaction": { /* transaction details */ },
  "review": { /* review details */ },
  "review_status": { /* review status details */ }
}
```

#### Update Review Status
```http
PUT /transaction-review/{review_id}/status
```

**Request Body:**
```json
{
  "status": "reviewed",
  "comment": "Investigated - false positive due to legitimate business transaction",
  "reviewer": "analyst@bank.com"
}
```

**Response:**
```json
{
  "message": "Review status updated successfully",
  "status": "reviewed"
}
```

### Analytics Endpoints

#### Get Analytics Summary
```http
GET /analytics/summary
```

**Response:**
```json
{
  "total_transactions": 1500,
  "suspicious_transactions": 45,
  "reviewed_transactions": 30,
  "pending_review": 15,
  "risk_distribution": {
    "high_risk": 12,
    "medium_risk": 20,
    "low_risk": 13
  },
  "pattern_distribution": {
    "louvain_community": 25,
    "cycle_detection": 20
  }
}
```

#### Get Network Graph
```http
GET /network-graph/{review_id}
```

**Response:**
```json
{
  "nodes": [
    {"id": "addr1", "label": "Address1..."},
    {"id": "addr2", "label": "Address2..."}
  ],
  "edges": [
    {
      "from": "addr1",
      "to": "addr2",
      "amount": 1000.0,
      "tx_id": "ABC123..."
    }
  ],
  "pattern_type": "cycle_detection",
  "cycle_length": 4
}
```

## AML Algorithm Details

### Louvain Community Detection

The system implements the Louvain algorithm to identify communities of accounts that frequently transact with each other:

1. **Graph Construction**: Creates a directed graph where nodes are addresses and edges are transactions
2. **Community Detection**: Applies Louvain algorithm to find densely connected groups
3. **Suspicious Pattern Analysis**: Identifies communities with:
   - Transactions within one year
   - Amounts below $10,000 threshold
   - High frequency of small transactions

### Cycle Detection

Detects circular transaction patterns that may indicate money laundering:

1. **Simple Cycle Detection**: Finds directed cycles up to 7 nodes
2. **Risk Assessment**: Evaluates cycles based on:
   - Cycle length (longer cycles = higher risk)
   - Total transaction amounts
   - Time patterns

### Risk Scoring

Risk scores (0-100) are calculated based on:
- **Community Analysis**: Community size, transaction frequency, amounts
- **Cycle Analysis**: Cycle length, total amounts, timing patterns
- **Threshold Analysis**: Transactions below reporting thresholds

## Configuration

### Environment Variables

Create a `.env` file with the following variables:

```env
# Database
DATABASE_URL=postgresql://aml_user:aml_password@postgres:5432/aml_db

# Observer API
OBSERVER_API_BASE=http://localhost:10052/api/observer
FETCH_INTERVAL=30

# Email Configuration
EMAIL_HOST=smtp.gmail.com
EMAIL_PORT=587
EMAIL_USER=your_email@gmail.com
EMAIL_PASSWORD=your_app_password
EMAIL_TO=admin@example.com

# AML Settings
AML_THRESHOLD=10000
```

### Email Setup

For Gmail:
1. Enable 2-factor authentication
2. Generate an app password
3. Use the app password in `EMAIL_PASSWORD`

## Monitoring and Alerts

### Email Alerts

The system sends automated email alerts for:
- **Hourly Alerts**: New suspicious transactions detected
- **Daily Summary**: Overall statistics and pending reviews

### Alert Content

Email alerts include:
- Risk scores and pattern types
- Transaction details (amount, addresses, dates)
- Analysis explanations
- Links to review interface

## Database Schema

### Tables

1. **transactions**: Stores all wRD and rRD transaction data
2. **transaction_reviews**: AML analysis results and risk scores
3. **review_status**: Review status and analyst comments

### Key Relationships

```sql
transactions (1) -> (many) transaction_reviews
transaction_reviews (1) -> (1) review_status
```

## Troubleshooting

### Common Issues

1. **Database Connection Error**:
   ```bash
   # Check if PostgreSQL is running
   docker-compose ps postgres
   
   # Check logs
   docker-compose logs postgres
   ```

2. **API Not Fetching Data**:
   ```bash
   # Check Observer API availability
   curl http://localhost:10052/api/observer/status
   
   # Check data fetcher logs
   docker-compose logs data-fetcher
   ```

3. **Email Alerts Not Working**:
   - Verify email credentials in `.env`
   - Check email service logs
   - Ensure firewall allows SMTP connections

### Logs

View service logs:
```bash
# All services
docker-compose logs

# Specific service
docker-compose logs aml-api
docker-compose logs data-fetcher
docker-compose logs aml-analyzer
docker-compose logs email-alerter
```

## Development

### Adding New Detection Algorithms

1. Create new analysis method in `services/aml_analyzer.py`
2. Update risk scoring logic
3. Add new pattern types to database schema
4. Update API responses

### Testing

```bash
# Install test dependencies
pip install pytest pytest-asyncio

# Run tests
pytest tests/
```

## Security Considerations

- Use strong database passwords
- Secure email credentials
- Implement API authentication for production
- Regular security updates
- Monitor access logs

## License

This project is for educational and compliance purposes. Ensure proper authorization before monitoring financial transactions.