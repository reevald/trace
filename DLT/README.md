# Digital Rupiah Trace (DLT) System

A Corda-based implementation of Indonesia's Central Bank Digital Currency (CBDC) system with comprehensive wallet management and traceability.

## System Overview

The DLT system implements a hierarchical digital currency structure:
- **wRD (Wholesale Digital Rupiah)** - For banks and financial institutions
- **rRD (Retail Digital Rupiah)** - For end users (Peritel and Retail)

### Network Participants
- **KDR (Bank Indonesia)** - Central bank issuing authority (Port 10006)
- **Wholesaler1** - Commercial bank (Port 10009)
- **Wholesaler2** - Commercial bank (Port 10012)
- **Wholesaler3** - Commercial bank (Port 10015)
- **Notary** - Transaction validation service (Port 10003)
- **Observer** - AML/compliance monitoring (Port 10018)

### Client APIs
- **KDR Client API** - Central bank operations (Port 10050)
- **Observer Client API** - Monitoring and reporting (Port 10052)
- **Notary Client API** - Transaction verification (Port 10051)

## Build and Deployment

### Prerequisites
- Java 17 or higher
- Gradle 7.6+
- 8GB RAM minimum

### Build and Deploy Nodes
```bash
./gradlew deployNodes
```

### Start Corda Network
```bash
cd build/nodes
./runnodes
```
Wait for all 6 nodes to start completely before proceeding.

### Start Client APIs
Open three separate terminals:

```bash
# Terminal 1: KDR Client API
./gradlew :clients:kdr-client:bootRun

# Terminal 2: Observer Client API  
./gradlew :clients:observer-client:bootRun

# Terminal 3: Notary Client API
./gradlew :clients:notary-client:bootRun
```

## Terminal Commands for 12 Flows

### Prerequisites for Terminal Commands
Connect to any node's shell:
```bash
# Connect to KDR node
ssh user1@localhost -p 2221
# Password: test
```

### [✅] 1. wRDCentralInitFlow
Initialize KDR's central wallet with initial supply.
```bash
# [Start from KDR Node]
flow start wRDCentralInitFlow initialAmount: "1000 IDR"
```

### [✅] 2. wRDIssuanceInitFlow
Issue wRD from KDR to wholesaler using specific source wallet.
```bash
# [Start from KDR Node]
flow start wRDIssuanceInitFlow wholesaler: "O=Wholesaler1,L=Jakarta,C=ID", amount: "69 IDR", sourceWalletId: 
"<walletId1>"

flow start wRDIssuanceInitFlow wholesaler: "O=Wholesaler2,L=Surabaya,C=ID", amount: "96 IDR", sourceWalletId: 
"<walletId2>"
```

### [✅] 3. wRDIssuanceFlow
Standard wRD issuance with walletId specification.
```bash
# [Start from KDR Node]
# Get walletId1 and walletId2
run vaultQuery contractStateType: com.trace.states.wRDAccountState

flow start wRDIssuanceFlow sourceWalletId: <centralWalletId>, receiverWalletId: <walletId1>, amount: "11 IDR"

flow start wRDIssuanceFlow sourceWalletId: <centralWalletId>, receiverWalletId: <walletId2>, amount: "4 IDR"
```

### [✅] 4. wRDTransferFlow
Transfer wRD between wholesaler wallets.
```bash
# [Start from Wholesaler 1 or 2]
flow start wRDTransferFlow sourceWalletId: <walletId1>, receiverWalletId: <walletId2>, receiverWholesaler: "O=Wholesaler2,L=Surabaya,C=ID", amount: "30 IDR"

flow start wRDTransferFlow sourceWalletId: <walletId2>, receiverWalletId: <walletId1>, receiverWholesaler: "O=Wholesaler1,L=Jakarta,C=ID", amount: "100 IDR"
```

### [✅] 5. wRDRedemptionFlow
Redeem wRD back to KDR.
```bash
# [Start from Wholesaler 1 or 2]
flow start wRDRedemptionFlow sourceWalletId: <walletId1>, receiverWalletId: <kdrWalletId>, amount: "100 IDR"

flow start wRDRedemptionFlow sourceWalletId: <walletId2>, receiverWalletId: <kdrWalletId>, amount: "10 IDR"
```

### 6. wRD2rRDIssuanceInitFlow
Convert wRD to rRD for peritel operations.
```bash
TODO
```

### 7. wRD2rRDIssuanceFlow
Standard wRD to rRD conversion using owner string.
```bash
TODO
```

### 8. rRDIssuanceInitFlow (with walletId)
Issue rRD from peritel to retail wallets.
```bash
TODO
```

### 9. rRDIssuanceFlow (legacy)
Standard rRD issuance using owner string.
```bash
TODO
```

### 10. rRDTransferFlow
Transfer rRD between retail users.
```bash
TODO
```

### 11. rRD2wRDRedemptionFlow (with walletId)
Convert rRD back to wRD.
```bash
TODO
```

### 12. rRDRedemptionFlow (legacy)
Redeem rRD using owner string.
```bash
TODO
```

## Vault Query Commands

### Query All wRD States
```bash
run vaultQuery contractStateType: com.trace.states.wRDAccountState
```

### Query All rRD States
```bash
run vaultQuery contractStateType: com.trace.states.rRDAccountState
```

### Query by Wallet ID
```bash
run vaultQuery contractStateType: com.trace.states.wRDAccountState, criteria: {linearId: {externalId: null, id: "a4dbb70a-6154-422b-a89f-8c8e5012235f"}}
```

### Query Unconsumed States Only
```bash
run vaultQuery contractStateType: com.trace.states.wRDAccountState, criteria: {status: UNCONSUMED}
```

### Query by Owner
```bash
run vaultQuery contractStateType: com.trace.states.wRDAccountState, criteria: {externalIds: ["O=KDR,L=Jakarta,C=ID"]}
```

## Client API Documentation

### KDR Client API (http://localhost:10050/api/kdr)

#### System Information Endpoints

**GET /status**
```bash
curl http://localhost:10050/api/kdr/status
```

**GET /me**
```bash
curl http://localhost:10050/api/kdr/me
```
Response:
```json
{
  "me": "O=KDR, L=Jakarta, C=ID"
}
```

**GET /peers**
```bash
curl http://localhost:10050/api/kdr/peers
```
Response:
```json
{
  "peers": [
    "O=Wholesaler1, L=Jakarta, C=ID",
    "O=Wholesaler2, L=Surabaya, C=ID",
    "O=Wholesaler3, L=Bandung, C=ID",
    "O=Observer, L=Jakarta, C=ID"
  ]
}
```

**GET /notaries**
```bash
curl http://localhost:10050/api/kdr/notaries
```

#### Wallet Management Endpoints

**GET /wallets**
```bash
curl http://localhost:10050/api/kdr/wallets
```
Response:
```json
[
  {
    "owner": "O=KDR, L=Jakarta, C=ID",
    "walletId": "a4dbb70a-6154-422b-a89f-8c8e5012235f",
    "amount": 99990000000000,
    "stateRef": "D18EB9E050542EFCA477BE3DD5111651D02E4C1732985FAD50AE36EB97A000C2(0)",
    "currency": "IDR",
    "tokenType": "IDR",
    "issuer": "O=KDR, L=Jakarta, C=ID"
  }
]
```

**GET /wallet/{walletId}**
```bash
curl http://localhost:10050/api/kdr/wallet/a4dbb70a-6154-422b-a89f-8c8e5012235f
```

**GET /all-wallets**
```bash
curl http://localhost:10050/api/kdr/all-wallets
```

**GET /total-balance**
```bash
curl http://localhost:10050/api/kdr/total-balance
```

#### Flow Operation Endpoints

**POST /central-init**
```bash
curl -X POST http://localhost:10050/api/kdr/central-init \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "amount=100000000000000&currency=IDR"
```
Request Body Parameters:
- `amount` (long): Initial amount in smallest currency unit
- `currency` (string): Currency code (IDR)

**POST /issuance-init**
```bash
curl -X POST http://localhost:10050/api/kdr/issuance-init \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "wholesaler=O=Wholesaler1,L=Jakarta,C=ID&amount=10000000000&currency=IDR&sourceWalletId=a4dbb70a-6154-422b-a89f-8c8e5012235f"
```
Request Body Parameters:
- `wholesaler` (string): Target wholesaler X500 name
- `amount` (long): Amount to issue
- `currency` (string): Currency code
- `sourceWalletId` (string, optional): Source wallet ID

**POST /issuance**
```bash
curl -X POST http://localhost:10050/api/kdr/issuance \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "partyName=O=Wholesaler1,L=Jakarta,C=ID&amount=10000000000&currency=IDR"
```
Request Body Parameters:
- `partyName` (string): Target party X500 name
- `amount` (long): Amount to issue
- `currency` (string): Currency code

**POST /transfer**
```bash
curl -X POST http://localhost:10050/api/kdr/transfer \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "partyName=O=Wholesaler2,L=Surabaya,C=ID&amount=1000000000&currency=IDR&sourceWalletId=source-id&targetWalletId=target-id"
```
Request Body Parameters:
- `partyName` (string): Target party X500 name
- `amount` (long): Transfer amount
- `currency` (string): Currency code
- `sourceWalletId` (string, optional): Source wallet ID
- `targetWalletId` (string, optional): Target wallet ID

**POST /wrd-to-rrd**
```bash
curl -X POST http://localhost:10050/api/kdr/wrd-to-rrd \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "amount=5000000000&currency=IDR&sourceWalletId=wrd-wallet-id&targetWalletId=rrd-wallet-id"
```
Request Body Parameters:
- `amount` (long): Conversion amount
- `currency` (string): Currency code
- `sourceWalletId` (string, optional): Source wRD wallet ID
- `targetWalletId` (string, optional): Target rRD wallet ID

**POST /rrd-issuance**
```bash
curl -X POST http://localhost:10050/api/kdr/rrd-issuance \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "amount=1000000000&currency=IDR&sourceWalletId=peritel-wallet-id&targetWalletId=retail-wallet-id"
```
Request Body Parameters:
- `amount` (long): Issuance amount
- `currency` (string): Currency code
- `sourceWalletId` (string, optional): Source peritel wallet ID
- `targetWalletId` (string, optional): Target retail wallet ID

**POST /redemption**
```bash
curl -X POST http://localhost:10050/api/kdr/redemption \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "amount=2000000000&currency=IDR&sourceWalletId=wholesaler-wallet-id"
```
Request Body Parameters:
- `amount` (long): Redemption amount
- `currency` (string): Currency code
- `sourceWalletId` (string, optional): Source wallet ID

**POST /rrd-to-wrd**
```bash
curl -X POST http://localhost:10050/api/kdr/rrd-to-wrd \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "amount=1000000000&currency=IDR&sourceWalletId=rrd-wallet-id&targetWalletId=wrd-wallet-id"
```
Request Body Parameters:
- `amount` (long): Conversion amount
- `currency` (string): Currency code
- `sourceWalletId` (string, optional): Source rRD wallet ID
- `targetWalletId` (string, optional): Target wRD wallet ID

### Observer Client API (http://localhost:10052/api/observer)

#### System Information

**GET /status**
```bash
curl http://localhost:10052/api/observer/status
```

**GET /me**
```bash
curl http://localhost:10052/api/observer/me
```

#### Transaction Monitoring

**GET /wrd-transactions**
```bash
curl http://localhost:10052/api/observer/wrd-transactions
```
Response: Array of wRD transaction details with wallet information.

**GET /rrd-transactions**
```bash
curl http://localhost:10052/api/observer/rrd-transactions
```
Response: Array of rRD transaction details with wallet information.

**GET /all-transactions**
```bash
curl http://localhost:10052/api/observer/all-transactions
```
Response: Combined array of all transaction types.

### Notary Client API (http://localhost:10051/api/notary)

#### System Information

**GET /status**
```bash
curl http://localhost:10051/api/notary/status
```

**GET /me**
```bash
curl http://localhost:10051/api/notary/me
```

**GET /notary-nodes**
```bash
curl http://localhost:10051/api/notary/notary-nodes
```
Response:
```json
[
  {
    "name": "O=Notary, L=Jakarta, C=ID",
    "organisation": "Notary",
    "locality": "Jakarta",
    "country": "ID",
    "publicKey": "...",
    "addresses": ["localhost:10002"]
  }
]
```

#### Transaction Verification

**GET /notarized-transactions**
```bash
curl http://localhost:10051/api/notary/notarized-transactions
```
Response: Array of notarized transactions with signature details.

**GET /transaction-details/{txId}**
```bash
curl http://localhost:10051/api/notary/transaction-details/D18EB9E050542EFCA477BE3DD5111651D02E4C1732985FAD50AE36EB97A000C2
```
Response: Detailed transaction information including notary signatures.

**GET /network-map**
```bash
curl http://localhost:10051/api/notary/network-map
```
Response: Network topology and node information.

## WalletId Architecture

### Wallet Types
1. **Central Wallets** - KDR's main issuance wallets
2. **Wholesaler Wallets** - Bank/institution wRD wallets
3. **Peritel Wallets** - Semi-retail rRD wallets
4. **Retail Wallets** - End-user rRD wallets

### Benefits
- **Multi-Wallet Support**: Entities can have multiple wallets
- **Precise Operations**: Target specific wallets for transactions
- **Complete Audit Trail**: Full traceability of wallet-to-wallet transfers
- **Backward Compatibility**: Legacy constructors still supported

### Usage Guidelines
- Always use walletId parameters for new implementations
- Query vault to get available wallet IDs before operations
- Validate wallet ownership before executing flows
- Use meaningful wallet identification patterns

## Error Handling

### Common HTTP Status Codes
- `200 OK` - Successful operation
- `201 Created` - Resource created successfully
- `400 Bad Request` - Invalid parameters or insufficient balance
- `404 Not Found` - Wallet or resource not found
- `500 Internal Server Error` - Flow execution error

### Common Validation Errors
- **Wallet Not Found**: Invalid walletId provided
- **Insufficient Balance**: Not enough funds in source wallet
- **Ownership Mismatch**: Wallet doesn't belong to initiating party
- **Type Mismatch**: Wrong wallet type for operation

## Troubleshooting

### Nodes Won't Start
- Verify Java 17+ is installed
- Check port availability (10002-10018)
- Ensure sufficient disk space (minimum 2GB)

### API Connection Fails
- Confirm all nodes are fully started
- Verify RPC port configuration
- Check firewall settings

### Flow Execution Errors
- Validate wallet IDs exist using vault queries
- Check sufficient balance in source wallets
- Ensure target parties are online and responsive

## Development and Testing

### Project Structure
```
├── contracts/          # Smart contracts and states
├── workflows/          # Flow implementations
├── clients/           # REST API clients
│   ├── kdr-client/    # Central bank API
│   ├── notary-client/ # Notary services API
│   └── observer-client/ # Monitoring API
└── build/             # Generated artifacts and nodes
```

### Running Tests
```bash
# Run all tests
./gradlew test

# Run specific test suite
./gradlew :workflows:test --tests "*WalletIdFlowTest*"
```

### Monitoring and Logs
- Node logs: `build/nodes/*/logs/`
- API logs: Available via client startup terminals
- Transaction monitoring: Observer Client API endpoints